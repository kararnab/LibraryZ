package recommendation

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"sort"
	"strings"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

// candidatePoolCap bounds the in-Go scans (content fallback + popular fill).
const candidatePoolCap = 500

// explainSimThreshold: minimum cosine between a recommended work and one of the
// user's liked works to justify a "Because you liked X" explanation.
const explainSimThreshold = 0.1

type Service struct {
	db *gorm.DB
}

func NewService(db *gorm.DB) *Service {
	return &Service{db: db}
}

// Recommendation is one suggested work plus why it surfaced.
type Recommendation struct {
	Work   catalog.Work `json:"work"`
	Reason string       `json:"reason"`
	Score  float64      `json:"score,omitempty"`
}

const (
	reasonAuthors        = "More from authors you've read"
	reasonTags           = "Based on tags in your library"
	reasonPopular        = "Popular on LibraryZ"
	reasonReadersLikeYou = "Readers like you also added this"
)

func reasonBecauseYouLiked(title string) string { return "Because you liked " + title }

// userBookRow is the slice of user_books we need; declared locally so this
// package doesn't import internal/library (mirrors library/contribution's
// raw-table decoupling).
type userBookRow struct {
	WorkID uuid.UUID `gorm:"column:work_id"`
	Status string    `gorm:"column:status"`
	Rating *int      `gorm:"column:rating"`
}

// Recommend returns up to `limit` works the user hasn't added or dismissed.
// The matrix-factorization model (userVec·workVec) is primary; when the user
// has no trained vector (cold start / pre-train) it falls back to the v0
// content+popularity scorer. Both paths get a diversity cap and a popular fill.
func (s *Service) Recommend(ctx context.Context, userID uint, limit int) ([]Recommendation, error) {
	if limit <= 0 {
		limit = 20
	}

	var rows []userBookRow
	if err := s.db.WithContext(ctx).
		Table("user_books").
		Select("work_id, status, rating").
		Where("user_id = ?", userID).
		Find(&rows).Error; err != nil {
		return nil, err
	}
	// `seen` excludes everything already in the library *and* anything dismissed.
	seen := make(map[uuid.UUID]struct{}, len(rows))
	for _, r := range rows {
		seen[r.WorkID] = struct{}{}
	}
	dismissed, err := s.dismissedSet(ctx, userID)
	if err != nil {
		return nil, err
	}
	for id := range dismissed {
		seen[id] = struct{}{}
	}

	popularity, err := s.popularityCounts(ctx)
	if err != nil {
		return nil, err
	}

	// Primary: matrix factorization. Returns nil (not error) when the user has
	// no trained vector, signalling the content fallback.
	ranked, err := s.recommendMF(ctx, userID, rows, seen)
	if err != nil {
		return nil, err
	}
	if ranked == nil {
		ranked, err = s.recommendContent(ctx, rows, seen, popularity)
		if err != nil {
			return nil, err
		}
	}

	// Diversity cap, then trim to limit.
	recs := applyDiversityCap(ranked, limit)

	// Fill short result sets with globally popular works.
	if len(recs) < limit {
		exclude := make(map[uuid.UUID]struct{}, len(seen)+len(recs))
		for id := range seen {
			exclude[id] = struct{}{}
		}
		for _, r := range recs {
			exclude[r.Work.ID] = struct{}{}
		}
		popular, err := s.popularWorks(ctx, popularity, exclude, limit-len(recs))
		if err != nil {
			return nil, err
		}
		for _, w := range popular {
			recs = append(recs, Recommendation{Work: w, Reason: reasonPopular})
		}
	}

	return recs, nil
}

// recommendMF scores unseen works by userVec·workVec and attaches a
// "Because you liked <title>" explanation (nearest liked work by cosine).
// Returns nil if the user has no trained factor row.
func (s *Service) recommendMF(ctx context.Context, userID uint, rows []userBookRow, seen map[uuid.UUID]struct{}) ([]Recommendation, error) {
	var uf UserFactors
	err := s.db.WithContext(ctx).First(&uf, "user_id = ?", userID).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil // no model for this user → fall back
	}
	if err != nil {
		return nil, err
	}
	userVec := decodeVec(uf.Vector)
	if len(userVec) == 0 {
		return nil, nil
	}

	var wfs []WorkFactors
	if err := s.db.WithContext(ctx).Find(&wfs).Error; err != nil {
		return nil, err
	}
	vecByWork := make(map[uuid.UUID][]float64, len(wfs))
	type scored struct {
		id    uuid.UUID
		score float64
	}
	var sc []scored
	for _, wf := range wfs {
		v := decodeVec(wf.Vector)
		vecByWork[wf.WorkID] = v
		if _, ok := seen[wf.WorkID]; ok {
			continue
		}
		sc = append(sc, scored{wf.WorkID, dot(userVec, v)})
	}
	if len(sc) == 0 {
		return []Recommendation{}, nil
	}
	sort.SliceStable(sc, func(i, j int) bool { return sc[i].score > sc[j].score })

	// Keep a generous candidate set (the diversity cap trims later).
	const candMultiple = 5
	limitGuess := 100
	if len(sc) > limitGuess*candMultiple {
		sc = sc[:limitGuess*candMultiple]
	}

	// Liked works (vectors + titles) for explanations.
	likedIDs := make([]uuid.UUID, 0, len(rows))
	for _, r := range rows {
		if likeWeight(r) > 0 {
			likedIDs = append(likedIDs, r.WorkID)
		}
	}
	likedTitles := s.titlesFor(ctx, likedIDs)

	ids := make([]uuid.UUID, len(sc))
	for i, c := range sc {
		ids[i] = c.id
	}
	works := s.worksByID(ctx, ids)

	recs := make([]Recommendation, 0, len(sc))
	for _, c := range sc {
		w, ok := works[c.id]
		if !ok {
			continue // factor row references a deleted work
		}
		reason := reasonReadersLikeYou
		// Nearest liked work by cosine over the in-memory vectors.
		bestSim, bestID := 0.0, uuid.Nil
		recVec := vecByWork[c.id]
		for _, lid := range likedIDs {
			lv := vecByWork[lid]
			if len(lv) == 0 {
				continue
			}
			if sim := cosineVec(recVec, lv); sim > bestSim {
				bestSim, bestID = sim, lid
			}
		}
		if bestSim >= explainSimThreshold {
			if title := likedTitles[bestID]; title != "" {
				reason = reasonBecauseYouLiked(title)
			}
		}
		recs = append(recs, Recommendation{Work: w, Reason: reason, Score: c.score})
	}
	return recs, nil
}

// recommendContent is the v0 fallback: shared authors/tags with the user's
// liked library items, rating-weighted. Returns the ranked content hits
// (uncapped, unfilled — the caller handles diversity + popular fill).
func (s *Service) recommendContent(ctx context.Context, rows []userBookRow, seen map[uuid.UUID]struct{}, popularity map[uuid.UUID]int) ([]Recommendation, error) {
	seedWeight := make(map[uuid.UUID]float64)
	for _, r := range rows {
		if w := likeWeight(r); w > 0 {
			seedWeight[r.WorkID] = w
		}
	}
	authorWeight := map[string]float64{}
	tagWeight := map[uuid.UUID]float64{}
	if len(seedWeight) > 0 {
		seedIDs := make([]uuid.UUID, 0, len(seedWeight))
		for id := range seedWeight {
			seedIDs = append(seedIDs, id)
		}
		var seedWorks []catalog.Work
		if err := s.db.WithContext(ctx).Preload("Tags").Where("id IN ?", seedIDs).Find(&seedWorks).Error; err != nil {
			return nil, err
		}
		for _, w := range seedWorks {
			wt := seedWeight[w.ID]
			for _, a := range authorTokens(w.Authors) {
				authorWeight[a] += wt
			}
			for _, t := range w.Tags {
				tagWeight[t.ID] += wt
			}
		}
	}
	if len(authorWeight) == 0 && len(tagWeight) == 0 {
		return []Recommendation{}, nil // pure cold start → caller fills popular
	}

	var pool []catalog.Work
	q := s.db.WithContext(ctx).Preload("Tags").Preload("Editions").
		Order("created_at DESC").Limit(candidatePoolCap)
	if len(seen) > 0 {
		q = q.Where("id NOT IN ?", keys(seen))
	}
	if err := q.Find(&pool).Error; err != nil {
		return nil, err
	}
	recs := []Recommendation{}
	for _, w := range pool {
		if score, reason := scoreWork(w, authorWeight, tagWeight); score > 0 {
			recs = append(recs, Recommendation{Work: w, Reason: reason, Score: score})
		}
	}
	sort.SliceStable(recs, func(i, j int) bool {
		if recs[i].Score != recs[j].Score {
			return recs[i].Score > recs[j].Score
		}
		pi, pj := popularity[recs[i].Work.ID], popularity[recs[j].Work.ID]
		if pi != pj {
			return pi > pj
		}
		return recs[i].Work.CreatedAt.After(recs[j].Work.CreatedAt)
	})
	return recs, nil
}

// applyDiversityCap greedily keeps the highest-ranked results while capping how
// many share a primary author or first tag, so the list isn't dominated by one
// author/series. A second pass fills any shortfall ignoring the cap, so we
// never return fewer than we could just to enforce diversity.
func applyDiversityCap(ranked []Recommendation, limit int) []Recommendation {
	const perFacetCap = 2
	counts := map[string]int{}
	used := map[uuid.UUID]bool{}
	out := make([]Recommendation, 0, limit)

	for _, r := range ranked {
		if len(out) >= limit {
			break
		}
		over := false
		for _, f := range facetKeys(r.Work) {
			if counts[f] >= perFacetCap {
				over = true
				break
			}
		}
		if over {
			continue
		}
		out = append(out, r)
		used[r.Work.ID] = true
		for _, f := range facetKeys(r.Work) {
			counts[f]++
		}
	}
	if len(out) < limit {
		for _, r := range ranked {
			if len(out) >= limit {
				break
			}
			if !used[r.Work.ID] {
				out = append(out, r)
				used[r.Work.ID] = true
			}
		}
	}
	return out
}

func facetKeys(w catalog.Work) []string {
	var ks []string
	if toks := authorTokens(w.Authors); len(toks) > 0 {
		ks = append(ks, "a:"+toks[0])
	}
	if len(w.Tags) > 0 {
		ks = append(ks, "t:"+w.Tags[0].ID.String())
	}
	return ks
}

// Dismiss hides a work from the user's future recommendations. Idempotent:
// re-dismissing the same work is a no-op (unique index on user_id+work_id).
func (s *Service) Dismiss(ctx context.Context, userID uint, workID uuid.UUID) error {
	d := Dismissal{ID: uuid.New(), UserID: userID, WorkID: workID}
	return s.db.WithContext(ctx).
		Clauses(clause.OnConflict{DoNothing: true}).
		Create(&d).Error
}

// dismissedSet returns the work ids the user has hidden.
func (s *Service) dismissedSet(ctx context.Context, userID uint) (map[uuid.UUID]struct{}, error) {
	var ids []uuid.UUID
	if err := s.db.WithContext(ctx).
		Model(&Dismissal{}).
		Where("user_id = ?", userID).
		Pluck("work_id", &ids).Error; err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]struct{}, len(ids))
	for _, id := range ids {
		out[id] = struct{}{}
	}
	return out, nil
}

func (s *Service) titlesFor(ctx context.Context, ids []uuid.UUID) map[uuid.UUID]string {
	out := map[uuid.UUID]string{}
	if len(ids) == 0 {
		return out
	}
	type row struct {
		ID    uuid.UUID `gorm:"column:id"`
		Title string    `gorm:"column:title"`
	}
	var rows []row
	if err := s.db.WithContext(ctx).Table("works").Select("id, title").Where("id IN ?", ids).Find(&rows).Error; err != nil {
		return out
	}
	for _, r := range rows {
		out[r.ID] = r.Title
	}
	return out
}

func (s *Service) worksByID(ctx context.Context, ids []uuid.UUID) map[uuid.UUID]catalog.Work {
	out := map[uuid.UUID]catalog.Work{}
	if len(ids) == 0 {
		return out
	}
	var works []catalog.Work
	if err := s.db.WithContext(ctx).Preload("Tags").Preload("Editions").Where("id IN ?", ids).Find(&works).Error; err != nil {
		return out
	}
	for _, w := range works {
		out[w.ID] = w
	}
	return out
}

func decodeVec(j []byte) []float64 {
	var v []float64
	_ = json.Unmarshal(j, &v)
	return v
}

func cosineVec(a, b []float64) float64 {
	if len(a) == 0 || len(b) == 0 {
		return 0
	}
	var d, na, nb float64
	for i := range a {
		d += a[i] * b[i]
		na += a[i] * a[i]
		nb += b[i] * b[i]
	}
	if na == 0 || nb == 0 {
		return 0
	}
	return d / (math.Sqrt(na) * math.Sqrt(nb))
}

// likeWeight scores how much a library row counts as a positive signal for the
// content fallback + explanation seeds. An explicit rating wins; otherwise
// reading state stands in. (Distinct from trainWeight, which counts every add.)
func likeWeight(r userBookRow) float64 {
	if r.Rating != nil {
		if *r.Rating >= 4 {
			return float64(*r.Rating)
		}
		return 0 // rated, but not a "like"
	}
	switch r.Status {
	case "read":
		return 4
	case "reading":
		return 3
	default: // want
		return 1
	}
}

func scoreWork(w catalog.Work, authorWeight map[string]float64, tagWeight map[uuid.UUID]float64) (float64, string) {
	var authorScore, tagScore float64
	for _, a := range authorTokens(w.Authors) {
		authorScore += authorWeight[a]
	}
	for _, t := range w.Tags {
		tagScore += tagWeight[t.ID]
	}
	total := authorScore + tagScore
	reason := reasonTags
	if authorScore >= tagScore {
		reason = reasonAuthors
	}
	return total, reason
}

func authorTokens(authors string) []string {
	if strings.TrimSpace(authors) == "" {
		return nil
	}
	parts := strings.Split(authors, ";")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		t := strings.ToLower(strings.TrimSpace(p))
		if t != "" {
			out = append(out, t)
		}
	}
	return out
}

// popularityCounts maps work_id -> number of users who have it in a library.
func (s *Service) popularityCounts(ctx context.Context) (map[uuid.UUID]int, error) {
	type row struct {
		WorkID uuid.UUID `gorm:"column:work_id"`
		N      int       `gorm:"column:n"`
	}
	var rows []row
	if err := s.db.WithContext(ctx).
		Table("user_books").
		Select("work_id, COUNT(*) AS n").
		Group("work_id").
		Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]int, len(rows))
	for _, r := range rows {
		out[r.WorkID] = r.N
	}
	return out, nil
}

// popularWorks returns up to n works not in `exclude`, ordered by library-add
// count desc, then most recent.
func (s *Service) popularWorks(ctx context.Context, popularity map[uuid.UUID]int, exclude map[uuid.UUID]struct{}, n int) ([]catalog.Work, error) {
	if n <= 0 {
		return nil, nil
	}
	var works []catalog.Work
	q := s.db.WithContext(ctx).Preload("Tags").Preload("Editions").
		Order("created_at DESC").Limit(candidatePoolCap)
	if len(exclude) > 0 {
		q = q.Where("id NOT IN ?", keys(exclude))
	}
	if err := q.Find(&works).Error; err != nil {
		return nil, err
	}
	sort.SliceStable(works, func(i, j int) bool {
		pi, pj := popularity[works[i].ID], popularity[works[j].ID]
		if pi != pj {
			return pi > pj
		}
		return works[i].CreatedAt.After(works[j].CreatedAt)
	})
	if len(works) > n {
		works = works[:n]
	}
	return works, nil
}

func keys(m map[uuid.UUID]struct{}) []uuid.UUID {
	out := make([]uuid.UUID, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}
