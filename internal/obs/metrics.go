package obs

import (
	"database/sql"
	"net/http"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Registry holds every LibraryZ metric plus the Go runtime and process
// collectors. It is served by MetricsHandler on its own listener
// (LIBRARYZ_METRICS_ADDR), never through Kong.
//
// Label values must stay bounded: route templates, status codes, job names,
// telemetry event names from the catalog. Never a user id, path, query or
// title.
var Registry = prometheus.NewRegistry()

var (
	httpRequests = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "libraryz_http_requests_total",
		Help: "HTTP requests by method, route template and status code.",
	}, []string{"method", "route", "status"})
	httpDuration = prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "libraryz_http_request_duration_seconds",
		Help:    "HTTP request latency by method and route template.",
		Buckets: []float64{.005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10, 30, 120},
	}, []string{"method", "route"})
	httpInFlight = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "libraryz_http_requests_in_flight",
		Help: "HTTP requests being served.",
	})
	httpPanics = prometheus.NewCounter(prometheus.CounterOpts{
		Name: "libraryz_http_panics_total",
		Help: "Handler panics recovered (each answered 500).",
	})
	jobRuns = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "libraryz_job_runs_total",
		Help: "Background job runs by job and result (ok, skipped, failed).",
	}, []string{"job", "result"})
	jobDuration = prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "libraryz_job_duration_seconds",
		Help:    "Background job run time, runs that did the work only.",
		Buckets: prometheus.ExponentialBuckets(0.01, 4, 10),
	}, []string{"job"})
	jobLastSuccess = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "libraryz_job_last_success_timestamp_seconds",
		Help: "Unix time of the job's last successful run on this instance.",
	}, []string{"job"})
)

func init() {
	Registry.MustRegister(
		collectors.NewGoCollector(),
		collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}),
		httpRequests, httpDuration, httpInFlight, httpPanics,
		jobRuns, jobDuration, jobLastSuccess,
	)
}

// MetricsHandler serves Registry in the Prometheus text format.
func MetricsHandler() http.Handler {
	return promhttp.HandlerFor(Registry, promhttp.HandlerOpts{Registry: Registry})
}

// RegisterDB exports the pool statistics of db (open, in use, idle, waits).
func RegisterDB(db *sql.DB) {
	Registry.MustRegister(collectors.NewDBStatsCollector(db, "libraryz"))
}

// Job results for JobDone.
const (
	JobOK      = "ok"
	JobSkipped = "skipped" // another instance holds the lock, or nothing to do
	JobFailed  = "failed"
)

// JobDone records one run of a background job that started at start.
func JobDone(job, result string, start time.Time) {
	jobRuns.WithLabelValues(job, result).Inc()
	if result == JobOK {
		jobDuration.WithLabelValues(job).Observe(time.Since(start).Seconds())
		jobLastSuccess.WithLabelValues(job).SetToCurrentTime()
	}
}
