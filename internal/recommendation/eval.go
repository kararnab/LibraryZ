package recommendation

import (
	"context"
	"math"
	"math/rand"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

// EvalResult holds leave-N-out holdout metrics for the MF system vs a pure
// popularity baseline, averaged over evaluated users.
type EvalResult struct {
	K            int
	Users        int
	MFHitRate    float64 // fraction of users with ≥1 held-out item in their top-K
	PopHitRate   float64
	MFPrecision  float64 // mean held-out items recovered / K
	PopPrecision float64
}

// Evaluate runs a destructive leave-N-out holdout against `db` (intended for a
// throwaway eval database): for each user with enough liked items it hides a
// `holdoutFrac` slice of them, trains MF on the remainder, then measures how
// well the deployed Recommend path (MF + fallback) recovers the hidden items
// vs ranking purely by global popularity. The held-out rows are deleted from
// user_books, so do NOT point this at a database you care about.
func Evaluate(ctx context.Context, db *gorm.DB, cfg Config, k int, holdoutFrac float64, seed int64) (EvalResult, error) {
	type row struct {
		UserID uint      `gorm:"column:user_id"`
		WorkID uuid.UUID `gorm:"column:work_id"`
		Status string    `gorm:"column:status"`
		Rating *int      `gorm:"column:rating"`
	}
	var rows []row
	if err := db.WithContext(ctx).Table("user_books").
		Select("user_id, work_id, status, rating").Find(&rows).Error; err != nil {
		return EvalResult{}, err
	}

	likedByUser := map[uint][]uuid.UUID{}
	for _, r := range rows {
		if likeWeight(userBookRow{WorkID: r.WorkID, Status: r.Status, Rating: r.Rating}) > 0 {
			likedByUser[r.UserID] = append(likedByUser[r.UserID], r.WorkID)
		}
	}

	rng := rand.New(rand.NewSource(seed))
	heldout := map[uint]map[uuid.UUID]bool{}
	var evalUsers []uint
	for u, items := range likedByUser {
		if len(items) < 2 {
			continue
		}
		nHold := int(math.Ceil(holdoutFrac * float64(len(items))))
		if nHold >= len(items) {
			nHold = len(items) - 1 // always leave ≥1 to train on
		}
		if nHold < 1 {
			continue
		}
		rng.Shuffle(len(items), func(i, j int) { items[i], items[j] = items[j], items[i] })
		h := make(map[uuid.UUID]bool, nHold)
		for _, id := range items[:nHold] {
			h[id] = true
		}
		heldout[u] = h
		evalUsers = append(evalUsers, u)
	}

	// Delete held-out interactions.
	for u, h := range heldout {
		for id := range h {
			if err := db.WithContext(ctx).
				Exec("DELETE FROM user_books WHERE user_id = ? AND work_id = ?", u, id).Error; err != nil {
				return EvalResult{}, err
			}
		}
	}

	if err := NewTrainer(db, cfg).Train(ctx); err != nil {
		return EvalResult{}, err
	}
	svc := NewService(db)
	popularity, err := svc.popularityCounts(ctx)
	if err != nil {
		return EvalResult{}, err
	}

	var mfHitUsers, popHitUsers, mfPrec, popPrec float64
	for _, u := range evalUsers {
		h := heldout[u]

		// MF arm: the deployed Recommend path.
		recs, err := svc.Recommend(ctx, u, k)
		if err != nil {
			return EvalResult{}, err
		}
		mfHits := 0
		for _, r := range recs {
			if h[r.Work.ID] {
				mfHits++
			}
		}

		// Popularity baseline: top-K most-added unseen works.
		var seenIDs []uuid.UUID
		if err := db.WithContext(ctx).Table("user_books").
			Where("user_id = ?", u).Pluck("work_id", &seenIDs).Error; err != nil {
			return EvalResult{}, err
		}
		exclude := make(map[uuid.UUID]struct{}, len(seenIDs))
		for _, id := range seenIDs {
			exclude[id] = struct{}{}
		}
		pop, err := svc.popularWorks(ctx, popularity, exclude, k)
		if err != nil {
			return EvalResult{}, err
		}
		popHits := 0
		for _, w := range pop {
			if h[w.ID] {
				popHits++
			}
		}

		if mfHits > 0 {
			mfHitUsers++
		}
		if popHits > 0 {
			popHitUsers++
		}
		mfPrec += float64(mfHits) / float64(k)
		popPrec += float64(popHits) / float64(k)
	}

	n := float64(len(evalUsers))
	if n == 0 {
		return EvalResult{K: k}, nil
	}
	return EvalResult{
		K:            k,
		Users:        len(evalUsers),
		MFHitRate:    mfHitUsers / n,
		PopHitRate:   popHitUsers / n,
		MFPrecision:  mfPrec / n,
		PopPrecision: popPrec / n,
	}, nil
}
