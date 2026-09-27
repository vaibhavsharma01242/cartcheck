package main

import (
	"strings"
	"testing"
)

func TestExtractJSON(t *testing.T) {
	out, err := extractJSON("```json\n{\"rating\":\"bad\",\"score\":20}\n```")
	if err != nil || string(out) != `{"rating":"bad","score":20}` {
		t.Fatalf("got %q, %v", out, err)
	}
	if _, err := extractJSON("no json here"); err == nil {
		t.Fatal("expected error")
	}
}

func TestBuildUserMessage(t *testing.T) {
	msg := buildUserMessage(analyzeRequest{App: "Swiggy", ScreenText: "Coke x1", Goals: goals{MaxCalories: 700}})
	for _, want := range []string{"Swiggy", "700 kcal", "max sugar not set", "Coke x1"} {
		if !strings.Contains(msg, want) {
			t.Errorf("message missing %q:\n%s", want, msg)
		}
	}
}
