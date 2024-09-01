package gateway

import (
	"bytes"
	"github.com/kararnab/libraryZ/pkg/config"
	"io"
	"net/http"
)

func SignUpHandler(w http.ResponseWriter, r *http.Request) {
	authServiceURL := "http://auth:" + config.GetAuthServicePort()
	if authServiceURL == "" {
		http.Error(w, "Authentication service URL is not set", http.StatusInternalServerError)
		return
	}

	// Read the request body
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusBadRequest)
		return
	}

	// Forward the request to the Authentication Service
	resp, err := http.Post(authServiceURL+"/signup", "application/json", bytes.NewBuffer(body))
	if err != nil {
		http.Error(w, "Failed to communicate with Authentication Service", http.StatusInternalServerError)
		return
	}
	defer resp.Body.Close()

	// Read the response from the Authentication Service
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		http.Error(w, "Failed to read response from Authentication Service", http.StatusInternalServerError)
		return
	}

	// Write the response back to the client
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(resp.StatusCode)
	_, err = w.Write(respBody)
	if err != nil {
		return
	}
}

// RecommendationHandler handles the /recommendations endpoint
func RecommendationHandler(w http.ResponseWriter, r *http.Request) {
	recommendationServiceURL := "http://recommendation:" + config.GetRecommendationServicePort()
	if recommendationServiceURL == "" {
		http.Error(w, "Recommendation service URL is not set", http.StatusInternalServerError)
		return
	}

	// Forward the request to the Recommendation Service
	req, err := http.NewRequest(http.MethodGet, recommendationServiceURL+"/recommendations", nil)
	if err != nil {
		http.Error(w, "Failed to create request to Recommendation Service", http.StatusInternalServerError)
		return
	}

	// Copy the Authorization header to the outgoing request
	req.Header.Set("Authorization", r.Header.Get("Authorization"))

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		http.Error(w, "Failed to communicate with Recommendation Service", http.StatusInternalServerError)
		return
	}
	defer resp.Body.Close()

	// Read the response from the Recommendation Service
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		http.Error(w, "Failed to read response from Recommendation Service", http.StatusInternalServerError)
		return
	}

	// Write the response back to the client
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(resp.StatusCode)
	w.Write(respBody)
}
