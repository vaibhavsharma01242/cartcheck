// CartCheck backend: receives OCR text of a food-delivery cart, asks Claude for a
// nutrition verdict, and returns compact JSON for the Android overlay.
package main

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

const (
	anthropicURL   = "https://api.anthropic.com/v1/messages"
	defaultModel   = "claude-haiku-4-5-20251001"
	maxScreenRunes = 6000
)

const systemPrompt = `You are CartCheck, a nutrition checker for Indian food-delivery and quick-commerce carts (Swiggy, Zomato, Blinkit).

You receive raw OCR text captured from a phone screen. It is noisy: broken lines, prices, fees, coupons, buttons, recommendations.

Steps:
1. Decide whether the text shows a cart/checkout containing food or drink items. If it does not, respond with exactly {"rating":"none"}.
2. Extract only the food/drink items actually in the cart, with quantities ("x2", "2 x", "Qty 2", stepper numbers). Ignore fees, taxes, tips, coupons, savings banners, "You may also like"/"Add more"/"Complete your meal" suggestions, and non-food products.
3. Estimate nutrition for each line INCLUDING quantity, using typical Indian restaurant portions, or the pack size shown (e.g. 750 ml, 200 g). Be realistic, not optimistic.
4. Compare totals with the user's goals and flag problem items: very high sugar, sugary drinks, deep-fried, ultra-processed, oversized portions, or anything conflicting with the goal notes.
5. Suggest one concrete, realistic swap the user could make on the same kind of app.

Rating: "good" = within goals, no major concerns. "caution" = near or modestly over a goal (up to ~25%), or one problem item. "bad" = clearly over a goal, or several problem items.

Respond with ONLY this JSON (no markdown, no commentary):
{"rating":"good|caution|bad","score":0-100,"headline":"<= 70 chars","totals":{"calories":int,"sugar_g":int,"protein_g":int,"fat_g":int},"items":[{"name":"string","qty":int,"calories":int,"sugar_g":int,"protein_g":int,"flag":"<= 40 chars or null"}],"suggestion":"<= 140 chars"}
All numbers are whole-number estimates. score: 100 = perfectly aligned with goals.`

type goals struct {
	MaxCalories int    `json:"max_calories"`
	MaxSugarG   int    `json:"max_sugar_g"`
	Notes       string `json:"notes"`
}

type analyzeRequest struct {
	App        string `json:"app"`
	ScreenText string `json:"screen_text"`
	Goals      goals  `json:"goals"`
}

type server struct {
	apiKey   string
	appToken string
	model    string
	client   *http.Client
}

func main() {
	s := &server{
		apiKey:   os.Getenv("ANTHROPIC_API_KEY"),
		appToken: os.Getenv("APP_TOKEN"),
		model:    getenv("MODEL", defaultModel),
		client:   &http.Client{Timeout: 45 * time.Second},
	}
	if s.apiKey == "" {
		log.Fatal("ANTHROPIC_API_KEY is not set")
	}
	if s.appToken == "" {
		log.Println("warning: APP_TOKEN not set — anyone who finds this URL can spend your API credits")
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok")) })
	mux.HandleFunc("/analyze", s.handleAnalyze)

	addr := ":" + getenv("PORT", "8080")
	log.Printf("CartCheck backend on %s (model %s)", addr, s.model)
	log.Fatal(http.ListenAndServe(addr, mux))
}

func (s *server) handleAnalyze(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST only")
		return
	}
	if s.appToken != "" && subtle.ConstantTimeCompare([]byte(r.Header.Get("X-App-Token")), []byte(s.appToken)) != 1 {
		writeErr(w, http.StatusUnauthorized, "bad app token")
		return
	}

	var req analyzeRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	req.ScreenText = truncateRunes(strings.TrimSpace(req.ScreenText), maxScreenRunes)
	if req.ScreenText == "" {
		writeErr(w, http.StatusBadRequest, "screen_text is empty")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 40*time.Second)
	defer cancel()

	start := time.Now()
	out, err := s.callClaude(ctx, buildUserMessage(req))
	if err != nil {
		log.Printf("analyze failed: %v", err)
		writeErr(w, http.StatusBadGateway, "analysis failed, try again")
		return
	}
	log.Printf("analyzed %s cart in %s", req.App, time.Since(start).Round(time.Millisecond))

	w.Header().Set("Content-Type", "application/json")
	w.Write(out)
}

func buildUserMessage(req analyzeRequest) string {
	limit := func(v int, unit string) string {
		if v <= 0 {
			return "not set"
		}
		return fmt.Sprintf("%d%s", v, unit)
	}
	notes := strings.TrimSpace(req.Goals.Notes)
	if notes == "" {
		notes = "none"
	}
	return fmt.Sprintf(
		"App: %s\nUser goals for this order: max calories %s, max sugar %s. Goal notes: %s\n\nOCR text of the screen:\n<<<\n%s\n>>>",
		orDefault(req.App, "unknown"), limit(req.Goals.MaxCalories, " kcal"), limit(req.Goals.MaxSugarG, " g"), notes, req.ScreenText,
	)
}

func (s *server) callClaude(ctx context.Context, userMsg string) ([]byte, error) {
	payload, err := json.Marshal(map[string]any{
		"model":      s.model,
		"max_tokens": 1500,
		"system":     systemPrompt,
		"messages":   []map[string]string{{"role": "user", "content": userMsg}},
	})
	if err != nil {
		return nil, err
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, anthropicURL, bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("x-api-key", s.apiKey)
	httpReq.Header.Set("anthropic-version", "2023-06-01")
	httpReq.Header.Set("content-type", "application/json")

	resp, err := s.client.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("anthropic API %d: %s", resp.StatusCode, raw)
	}

	var parsed struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return nil, err
	}
	var sb strings.Builder
	for _, c := range parsed.Content {
		if c.Type == "text" {
			sb.WriteString(c.Text)
		}
	}
	return extractJSON(sb.String())
}

// extractJSON pulls the first {...} object out of the model output (tolerates stray fences/text).
func extractJSON(s string) ([]byte, error) {
	start, end := strings.Index(s, "{"), strings.LastIndex(s, "}")
	if start < 0 || end <= start {
		return nil, errors.New("no JSON object in model output")
	}
	candidate := []byte(s[start : end+1])
	if !json.Valid(candidate) {
		return nil, errors.New("model returned invalid JSON")
	}
	return candidate, nil
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func truncateRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func orDefault(v, def string) string {
	if strings.TrimSpace(v) == "" {
		return def
	}
	return v
}
