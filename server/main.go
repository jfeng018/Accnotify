package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/accnotify/server/config"
	"github.com/accnotify/server/handler"
	"github.com/accnotify/server/storage"
)

func main() {
	// Load configuration
	cfg := config.LoadFromEnv()

	// Initialize storage
	store, err := storage.NewSQLiteStorage(cfg.DBPath)
	if err != nil {
		log.Fatalf("Failed to initialize storage: %v", err)
	}
	defer store.Close()

	// Initialize WebSocket hub
	hub := handler.NewHub(store)
	go hub.Run()

	// Initialize handlers
	pushHandler := handler.NewPushHandler(store, hub)
	wsHandler := handler.NewWSHandler(hub, store)
	webhookHandler := handler.NewWebhookHandler(store, hub)

	// Setup Gin router
	gin.SetMode(gin.ReleaseMode)
	router := gin.Default()

	// CORS middleware
	router.Use(func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}
		c.Next()
	})

	// Routes
	router.GET("/health", pushHandler.HandleHealth)
	router.POST("/register", pushHandler.HandleRegister)
	router.POST("/push/:device_key", pushHandler.HandlePush)
	router.GET("/push/:device_key", pushHandler.HandlePush)
	router.GET("/push/:device_key/*params", handleSimplePushParams(pushHandler))
	router.GET("/ws", func(c *gin.Context) {
		wsHandler.HandleConnect(c.Writer, c.Request)
	})

	// Webhook routes
	webhookGroup := router.Group("/webhook/:device_key")
	{
		webhookGroup.POST("", webhookHandler.HandleGenericWebhook)
		webhookGroup.POST("/github", webhookHandler.HandleGitHubWebhook)
		webhookGroup.POST("/gitlab", webhookHandler.HandleGitLabWebhook)
		webhookGroup.POST("/docker", webhookHandler.HandleDockerHubWebhook)
		webhookGroup.POST("/gitea", webhookHandler.HandleGiteaWebhook)
	}

	// Create server
	addr := fmt.Sprintf("%s:%d", cfg.Host, cfg.Port)
	srv := &http.Server{
		Addr:    addr,
		Handler: router,
	}

	// Start server in goroutine
	go func() {
		log.Printf("Accnotify server starting on %s", addr)
		var err error
		if cfg.EnableHTTPS {
			err = srv.ListenAndServeTLS(cfg.CertFile, cfg.KeyFile)
		} else {
			err = srv.ListenAndServe()
		}
		if err != nil && err != http.ErrServerClosed {
			log.Fatalf("Server error: %v", err)
		}
	}()

	// Wait for interrupt signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("Shutting down server...")

	// Graceful shutdown
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		log.Fatalf("Server forced to shutdown: %v", err)
	}

	log.Println("Server exited")
}

// handleSimplePushParams handles /push/:device_key/*params
// Supports: /push/key/body OR /push/key/title/body
func handleSimplePushParams(h *handler.PushHandler) gin.HandlerFunc {
	return func(c *gin.Context) {
		params := c.Param("params")
		// Remove leading slash
		if len(params) > 0 && params[0] == '/' {
			params = params[1:]
		}
		
		// Split by /
		parts := strings.SplitN(params, "/", 2)
		
		var title, body string
		if len(parts) == 1 {
			// Only body provided
			title = "Accnotify"
			body = parts[0]
		} else {
			// Title and body provided
			title = parts[0]
			body = parts[1]
		}
		
		c.Params = append(c.Params, gin.Param{Key: "title", Value: title})
		c.Params = append(c.Params, gin.Param{Key: "body", Value: body})
		h.HandleSimplePush(c)
	}
}
