package handler

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/accnotify/server/crypto"
	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
)

// PushHandler handles push notification requests
type PushHandler struct {
	storage *storage.SQLiteStorage
	hub     *Hub
	crypto  *crypto.Crypto
}

// NewPushHandler creates a new push handler
func NewPushHandler(storage *storage.SQLiteStorage, hub *Hub) *PushHandler {
	return &PushHandler{
		storage: storage,
		hub:     hub,
		crypto:  crypto.NewCrypto(),
	}
}

// HandlePush handles POST /push/:device_key
// Supports JSON body, form-encoded body, and query parameters (Bark-compatible)
func (h *PushHandler) HandlePush(c *gin.Context) {
	deviceKey := c.Param("device_key")
	if deviceKey == "" {
		c.JSON(http.StatusBadRequest, model.PushResponse{
			Success: false,
			Error:   "Missing device key",
		})
		return
	}

	// Get device
	device, err := h.storage.GetDeviceByKey(deviceKey)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.PushResponse{
			Success: false,
			Error:   "Database error",
		})
		return
	}
	if device == nil {
		c.JSON(http.StatusNotFound, model.PushResponse{
			Success: false,
			Error:   "Device not found",
		})
		return
	}

	// Parse request - support JSON, form, and query params (Bark-compatible)
	var req model.PushRequest
	contentType := c.GetHeader("Content-Type")
	if strings.Contains(contentType, "application/json") {
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, model.PushResponse{
				Success: false,
				Error:   "Invalid request body",
			})
			return
		}
	} else if strings.Contains(contentType, "application/x-www-form-urlencoded") || strings.Contains(contentType, "multipart/form-data") {
		// Form-encoded (Bark-compatible)
		req.Title = c.PostForm("title")
		req.Body = c.PostForm("body")
		req.Group = c.PostForm("group")
		req.Icon = c.PostForm("icon")
		req.URL = c.PostForm("url")
		req.Image = c.PostForm("image")
		req.Sound = c.PostForm("sound")
	} else {
		// Try JSON first, fallback to empty
		_ = c.ShouldBindJSON(&req)
	}

	// Override with query parameters if present (Bark-compatible)
	if v := c.Query("title"); v != "" {
		req.Title = v
	}
	if v := c.Query("body"); v != "" {
		req.Body = v
	}
	if v := c.Query("group"); v != "" {
		req.Group = v
	}
	if v := c.Query("icon"); v != "" {
		req.Icon = v
	}
	if v := c.Query("url"); v != "" {
		req.URL = v
	}
	if v := c.Query("image"); v != "" {
		req.Image = v
	}
	if v := c.Query("sound"); v != "" {
		req.Sound = v
	}

	// Generate message ID
	messageID := uuid.New().String()

	// Replace literal \n with actual newlines
	req.Body = strings.ReplaceAll(req.Body, `\n`, "\n")
	req.Title = strings.ReplaceAll(req.Title, `\n`, "\n")

	// Create message
	msg := &model.Message{
		DeviceID:  device.ID,
		MessageID: messageID,
		Title:     req.Title,
		Body:      req.Body,
		Group:     req.Group,
		Icon:      req.Icon,
		URL:       req.URL,
		Image:     req.Image,
		Sound:     req.Sound,
		Badge:     req.Badge,
	}

	// Encrypt if device has public key (E2E mode)
	var encryptedContent string
	if device.PublicKey != "" {
		publicKey, err := h.crypto.ParsePublicKey(device.PublicKey)
		if err == nil {
			// Create payload to encrypt
			payload := map[string]interface{}{
				"title": req.Title,
				"body":  req.Body,
				"group": req.Group,
				"icon":  req.Icon,
				"url":   req.URL,
				"image": req.Image,
				"sound": req.Sound,
				"badge": req.Badge,
			}
			payloadBytes, _ := json.Marshal(payload)
			encryptedContent, _ = h.crypto.EncryptMessage(publicKey, payloadBytes)
			msg.EncryptedPayload = []byte(encryptedContent)
		}
	}

	// Store message
	if err := h.storage.CreateMessage(msg); err != nil {
		c.JSON(http.StatusInternalServerError, model.PushResponse{
			Success: false,
			Error:   "Failed to store message",
		})
		return
	}

	// Prepare WebSocket message
	wsMsg := &model.WSMessage{
		Type:      model.WSTypeMessage,
		ID:        messageID,
		Timestamp: time.Now().Unix(),
		Data: map[string]interface{}{
			"title":             req.Title,
			"body":              req.Body,
			"group":             req.Group,
			"icon":              req.Icon,
			"url":               req.URL,
			"image":             req.Image,
			"sound":             req.Sound,
			"badge":             req.Badge,
			"encrypted_content": encryptedContent,
		},
	}

	// Send via WebSocket
	delivered := h.hub.SendToDevice(deviceKey, wsMsg)

	if delivered {
		h.storage.MarkMessageDelivered(messageID)
	}

	c.JSON(http.StatusOK, model.PushResponse{
		Success:   true,
		MessageID: messageID,
	})
}

// HandleSimplePush handles GET /push/:device_key/:title/:body (Bark-compatible)
func (h *PushHandler) HandleSimplePush(c *gin.Context) {
	deviceKey := c.Param("device_key")
	title := c.Param("title")
	body := c.Param("body")

	// If only one param, treat it as body
	if body == "" {
		body = title
		title = "Accnotify"
	}

	// Convert to POST request
	c.Set("device_key", deviceKey)

	// Get device
	device, err := h.storage.GetDeviceByKey(deviceKey)
	if err != nil || device == nil {
		c.JSON(http.StatusNotFound, model.PushResponse{
			Success: false,
			Error:   "Device not found",
		})
		return
	}

	messageID := uuid.New().String()

	msg := &model.Message{
		DeviceID:  device.ID,
		MessageID: messageID,
		Title:     title,
		Body:      body,
	}

	// Support additional query params for GET requests (Bark-compatible)
	group := c.Query("group")
	icon := c.Query("icon")
	url := c.Query("url")
	image := c.Query("image")
	sound := c.Query("sound")
	msg.Group = group
	msg.Icon = icon
	msg.URL = url
	msg.Image = image
	msg.Sound = sound

	// Encrypt if device has public key
	var encryptedContent string
	if device.PublicKey != "" {
		publicKey, err := h.crypto.ParsePublicKey(device.PublicKey)
		if err == nil {
			payload := map[string]interface{}{
				"title": title,
				"body":  body,
				"group": group,
				"icon":  icon,
				"url":   url,
				"image": image,
				"sound": sound,
			}
			payloadBytes, _ := json.Marshal(payload)
			encryptedContent, _ = h.crypto.EncryptMessage(publicKey, payloadBytes)
			msg.EncryptedPayload = []byte(encryptedContent)
		}
	}

	if err := h.storage.CreateMessage(msg); err != nil {
		c.JSON(http.StatusInternalServerError, model.PushResponse{
			Success: false,
			Error:   "Failed to store message",
		})
		return
	}

	wsMsg := &model.WSMessage{
		Type:      model.WSTypeMessage,
		ID:        messageID,
		Timestamp: time.Now().Unix(),
		Data: map[string]interface{}{
			"title":             title,
			"body":              body,
			"group":             group,
			"icon":              icon,
			"url":               url,
			"image":             image,
			"sound":             sound,
			"encrypted_content": encryptedContent,
		},
	}

	delivered := h.hub.SendToDevice(deviceKey, wsMsg)
	if delivered {
		h.storage.MarkMessageDelivered(messageID)
	}

	c.JSON(http.StatusOK, model.PushResponse{
		Success:   true,
		MessageID: messageID,
	})
}

// HandleRegister handles POST /register
func (h *PushHandler) HandleRegister(c *gin.Context) {
	var req model.RegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"success": false,
			"error":   "Invalid request body",
		})
		return
	}

	// Check if device exists
	device, err := h.storage.GetDeviceByKey(req.DeviceKey)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"success": false,
			"error":   "Database error",
		})
		return
	}

	if device != nil {
		// Update public key
		if req.PublicKey != "" {
			if err := h.storage.UpdateDevicePublicKey(req.DeviceKey, req.PublicKey); err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{
					"success": false,
					"error":   "Failed to update public key",
				})
				return
			}
		}
		c.JSON(http.StatusOK, gin.H{
			"success":    true,
			"device_key": req.DeviceKey,
			"message":    "Device updated",
		})
		return
	}

	// Create new device
	newDevice := &model.Device{
		DeviceKey: req.DeviceKey,
		PublicKey: req.PublicKey,
		Name:      req.Name,
	}

	if err := h.storage.CreateDevice(newDevice); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"success": false,
			"error":   "Failed to create device",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"success":    true,
		"device_key": req.DeviceKey,
		"message":    "Device registered",
	})
}

// HandleHealth handles GET /health
func (h *PushHandler) HandleHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":    "ok",
		"timestamp": time.Now().Unix(),
	})
}
