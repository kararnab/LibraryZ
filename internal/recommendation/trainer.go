package recommendation

import (
	"context"
	"encoding/json"
	"math"
	"math/rand"
	"sort"
	"time"

	"github.com/google/uuid"
	"gonum.org/v1/gonum/mat"
	"gorm.io/datatypes"
	"gorm.io/gorm"
)

// Config controls the implicit-ALS training. Defaults are tuned for a small
// corpus; all are env-overridable via pkg/config and Service/Trainer wiring.
type Config struct {
	Factors       int     // latent dimension k
	Iterations    int     // ALS sweeps
	Lambda        float64 // L2 regularization
	Alpha         float64 // confidence scaling: c = 1 + alpha*weight
	NeighborsTopN int     // item-item edges stored per work
	Seed          int64   // RNG seed for reproducible factors
}

func DefaultConfig() Config {
	return Config{Factors: 32, Iterations: 15, Lambda: 0.1, Alpha: 40, NeighborsTopN: 20, Seed: 42}
}

// Trainer fits an implicit-feedback ALS model (Hu–Koren–Volinsky) over the
// user_books interaction matrix and writes the factor + neighbor tables.
type Trainer struct {
	db  *gorm.DB
	cfg Config
}

func NewTrainer(db *gorm.DB, cfg Config) *Trainer { return &Trainer{db: db, cfg: cfg} }

type trainRow struct {
	UserID uint      `gorm:"column:user_id"`
	WorkID uuid.UUID `gorm:"column:work_id"`
	Status string    `gorm:"column:status"`
	Rating *int      `gorm:"column:rating"`
}

// trainWeight is the implicit-feedback strength of one interaction. Unlike the
// serving-side likeWeight (which zeroes non-likes for seed selection), every
// library add is a positive signal here; rating/status sets its confidence.
func trainWeight(status string, rating *int) float64 {
	if rating != nil {
		return float64(*rating) // 1..5
	}
	switch status {
	case "read":
		return 3
	case "reading":
		return 2
	default: // want
		return 1
	}
}

type entry struct {
	idx  int
	conf float64
}

// Train fits the model and atomically replaces the factor/neighbor tables.
// A corpus with no interactions clears the tables (serving then falls back to
// the v0 content+popularity path).
func (t *Trainer) Train(ctx context.Context) error {
	var rows []trainRow
	if err := t.db.WithContext(ctx).
		Table("user_books").
		Select("user_id, work_id, status, rating").
		Find(&rows).Error; err != nil {
		return err
	}
	if len(rows) == 0 {
		return t.persist(ctx, nil, nil, nil)
	}

	// Index users and items to dense 0..n ranges.
	userIdx := map[uint]int{}
	workIdx := map[uuid.UUID]int{}
	var userIDs []uint
	var workIDs []uuid.UUID
	for _, r := range rows {
		if _, ok := userIdx[r.UserID]; !ok {
			userIdx[r.UserID] = len(userIDs)
			userIDs = append(userIDs, r.UserID)
		}
		if _, ok := workIdx[r.WorkID]; !ok {
			workIdx[r.WorkID] = len(workIDs)
			workIDs = append(workIDs, r.WorkID)
		}
	}
	nUsers, nItems := len(userIDs), len(workIDs)

	itemsByUser := make([][]entry, nUsers)
	usersByItem := make([][]entry, nItems)
	for _, r := range rows {
		u, i := userIdx[r.UserID], workIdx[r.WorkID]
		conf := 1 + t.cfg.Alpha*trainWeight(r.Status, r.Rating)
		itemsByUser[u] = append(itemsByUser[u], entry{idx: i, conf: conf})
		usersByItem[i] = append(usersByItem[i], entry{idx: u, conf: conf})
	}

	k := t.cfg.Factors
	rng := rand.New(rand.NewSource(t.cfg.Seed))
	X := randDense(nUsers, k, rng) // user factors
	Y := randDense(nItems, k, rng) // item factors

	for it := 0; it < t.cfg.Iterations; it++ {
		yty := gram(Y)
		for u := 0; u < nUsers; u++ {
			solveRow(X, u, yty, Y, itemsByUser[u], k, t.cfg.Lambda)
		}
		xtx := gram(X)
		for i := 0; i < nItems; i++ {
			solveRow(Y, i, xtx, X, usersByItem[i], k, t.cfg.Lambda)
		}
	}

	// Build factor rows.
	workFactors := make([]WorkFactors, nItems)
	now := time.Now()
	for i, id := range workIDs {
		workFactors[i] = WorkFactors{WorkID: id, Vector: toJSON(Y.RawRowView(i)), UpdatedAt: now}
	}
	userFactors := make([]UserFactors, nUsers)
	for u, id := range userIDs {
		userFactors[u] = UserFactors{UserID: id, Vector: toJSON(X.RawRowView(u)), UpdatedAt: now}
	}

	neighbors := buildNeighbors(Y, workIDs, t.cfg.NeighborsTopN)

	return t.persist(ctx, workFactors, userFactors, neighbors)
}

// buildNeighbors computes each work's top-N most cosine-similar works.
// All-pairs O(items^2 * k) — acceptable offline at small corpus size.
func buildNeighbors(Y *mat.Dense, workIDs []uuid.UUID, topN int) []WorkNeighbor {
	n := len(workIDs)
	norms := make([]float64, n)
	for i := 0; i < n; i++ {
		row := Y.RawRowView(i)
		norms[i] = math.Sqrt(dot(row, row))
	}
	var out []WorkNeighbor
	type cand struct {
		j     int
		score float64
	}
	for i := 0; i < n; i++ {
		if norms[i] == 0 {
			continue
		}
		yi := Y.RawRowView(i)
		cands := make([]cand, 0, n-1)
		for j := 0; j < n; j++ {
			if j == i || norms[j] == 0 {
				continue
			}
			cands = append(cands, cand{j: j, score: dot(yi, Y.RawRowView(j)) / (norms[i] * norms[j])})
		}
		sort.Slice(cands, func(a, b int) bool { return cands[a].score > cands[b].score })
		if len(cands) > topN {
			cands = cands[:topN]
		}
		for _, c := range cands {
			out = append(out, WorkNeighbor{WorkID: workIDs[i], NeighborID: workIDs[c.j], Score: c.score})
		}
	}
	return out
}

// persist replaces all three tables in one transaction so readers never see a
// half-trained model.
func (t *Trainer) persist(ctx context.Context, wf []WorkFactors, uf []UserFactors, nb []WorkNeighbor) error {
	return t.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		for _, tbl := range []string{"rec_work_factors", "rec_user_factors", "rec_work_neighbors"} {
			if err := tx.Exec("DELETE FROM " + tbl).Error; err != nil {
				return err
			}
		}
		if len(wf) > 0 {
			if err := tx.CreateInBatches(wf, 200).Error; err != nil {
				return err
			}
		}
		if len(uf) > 0 {
			if err := tx.CreateInBatches(uf, 200).Error; err != nil {
				return err
			}
		}
		if len(nb) > 0 {
			if err := tx.CreateInBatches(nb, 200).Error; err != nil {
				return err
			}
		}
		return nil
	})
}

// --- linear algebra helpers (k is small; gonum keeps the solves clean) ---

func randDense(rows, k int, rng *rand.Rand) *mat.Dense {
	data := make([]float64, rows*k)
	for i := range data {
		data[i] = rng.NormFloat64() * 0.01
	}
	if rows == 0 {
		return mat.NewDense(0, k, nil)
	}
	return mat.NewDense(rows, k, data)
}

// gram returns MᵀM (k×k).
func gram(m *mat.Dense) *mat.Dense {
	var g mat.Dense
	g.Mul(m.T(), m)
	return &g
}

// solveRow solves (GtG + Σ(c-1)·yyᵀ + λI) x = Σ c·y for the given row's
// interactions and writes x into dst[row]. GtG is the gram matrix of the
// opposite factor set; y rows come from `other`.
func solveRow(dst *mat.Dense, row int, gtg *mat.Dense, other *mat.Dense, entries []entry, k int, lambda float64) {
	var A mat.Dense
	A.CloneFrom(gtg)
	b := make([]float64, k)
	for _, e := range entries {
		y := other.RawRowView(e.idx)
		cm1 := e.conf - 1
		for a := 0; a < k; a++ {
			ya := y[a]
			for col := 0; col < k; col++ {
				A.Set(a, col, A.At(a, col)+cm1*ya*y[col])
			}
			b[a] += e.conf * ya
		}
	}
	for d := 0; d < k; d++ {
		A.Set(d, d, A.At(d, d)+lambda)
	}
	var x mat.VecDense
	if err := x.SolveVec(&A, mat.NewVecDense(k, b)); err != nil {
		return // singular: keep prior row (init / last iteration)
	}
	dst.SetRow(row, x.RawVector().Data)
}

func dot(a, b []float64) float64 {
	var s float64
	for i := range a {
		s += a[i] * b[i]
	}
	return s
}

func toJSON(v []float64) datatypes.JSON {
	// Copy: RawRowView aliases the backing array.
	cp := make([]float64, len(v))
	copy(cp, v)
	raw, _ := json.Marshal(cp)
	return datatypes.JSON(raw)
}
