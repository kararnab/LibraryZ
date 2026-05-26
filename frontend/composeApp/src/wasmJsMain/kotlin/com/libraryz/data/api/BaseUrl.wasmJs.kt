package com.libraryz.data.api

// Browser hits the backend directly; CORS must be enabled on the backend
// in dev (it is — see internal/server/server.go).
actual val DefaultBaseUrl: String = "http://localhost:8080"
