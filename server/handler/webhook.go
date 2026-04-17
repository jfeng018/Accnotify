package handler

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"github.com/accnotify/server/crypto"
	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// WebhookHandler handles webhook requests from various services
type WebhookHandler struct {
	storage *storage.SQLiteStorage
	hub     *Hub
	crypto  *crypto.Crypto
}

// NewWebhookHandler creates a new webhook handler
func NewWebhookHandler(storage *storage.SQLiteStorage, hub *Hub) *WebhookHandler {
	return &WebhookHandler{
		storage: storage,
		hub:     hub,
		crypto:  crypto.NewCrypto(),
	}
}

// HandleGenericWebhook handles POST /webhook/:device_key (generic webhook)
func (h *WebhookHandler) HandleGenericWebhook(c *gin.Context) {
	deviceKey := c.Param("device_key")
	if deviceKey == "" {
		c.JSON(http.StatusBadRequest, model.PushResponse{
			Success: false,
			Error:   "Missing device key",
		})
		return
	}

	// Verify device exists
	device, err := h.storage.GetDeviceByKey(deviceKey)
	if err != nil || device == nil {
		c.JSON(http.StatusNotFound, model.PushResponse{
			Success: false,
			Error:   "Device not found",
		})
		return
	}

	// Read raw body
	body, err := io.ReadAll(c.Request.Body)
	if err != nil {
		c.JSON(http.StatusBadRequest, model.PushResponse{
			Success: false,
			Error:   "Failed to read request body",
		})
		return
	}

	// Try to parse as JSON
	var jsonData map[string]interface{}
	if err := json.Unmarshal(body, &jsonData); err == nil {
		// 智能解析 JSON 内容，提取有意义的标题和正文
		title, parsedBody := h.parseGenericJSON(jsonData)
		// Extract image field if present
		image, _ := jsonData["image"].(string)
		h.sendWebhookMessage(deviceKey, device, title, parsedBody, image, c)
		return
	}

	// If not JSON, send as plain text
	h.sendWebhookMessage(deviceKey, device, "Webhook", string(body), "", c)
}

// parseGenericJSON attempts to extract meaningful title and body from generic JSON
func (h *WebhookHandler) parseGenericJSON(data map[string]interface{}) (title string, body string) {
	// 尝试提取标题 - 按优先级检查常见字段
	titleFields := []string{
		"title", "Title", "TITLE",
		"subject", "Subject", "SUBJECT",
		"name", "Name", "NAME",
		"event", "Event", "EVENT",
		"event_type", "eventType", "EventType",
		"action", "Action", "ACTION",
		"type", "Type", "TYPE",
		"alert_name", "alertName", "AlertName",
	}

	for _, field := range titleFields {
		if val, ok := data[field]; ok {
			if strVal, ok := val.(string); ok && strVal != "" {
				title = strVal
				break
			}
		}
	}

	// 尝试提取正文 - 按优先级检查常见字段
	bodyFields := []string{
		"body", "Body", "BODY",
		"message", "Message", "MESSAGE",
		"content", "Content", "CONTENT",
		"text", "Text", "TEXT",
		"description", "Description", "DESCRIPTION",
		"summary", "Summary", "SUMMARY",
		"details", "Details", "DETAILS",
		"data", "Data", "DATA",
		"payload", "Payload", "PAYLOAD",
	}

	var extractedBody string
	for _, field := range bodyFields {
		if val, ok := data[field]; ok {
			switch v := val.(type) {
			case string:
				if v != "" {
					extractedBody = v
					break
				}
			case map[string]interface{}:
				// 嵌套对象，格式化为 JSON
				formatted, _ := json.MarshalIndent(v, "", "  ")
				extractedBody = string(formatted)
				break
			}
		}
		if extractedBody != "" {
			break
		}
	}

	// 如果没有找到正文，使用格式化的完整 JSON
	if extractedBody == "" {
		formatted, _ := json.MarshalIndent(data, "", "  ")
		extractedBody = string(formatted)
	}

	// 如果没有找到标题，使用默认值
	if title == "" {
		title = "Webhook"
	} else {
		title = "Webhook: " + title
	}

	// 构建最终消息体
	// 如果提取了特定字段，同时附上完整 JSON 供参考
	if extractedBody != "" {
		formatted, _ := json.MarshalIndent(data, "", "  ")
		fullJSON := string(formatted)
		if extractedBody != fullJSON {
			body = fmt.Sprintf("%s\n\n--- 完整数据 ---\n%s", extractedBody, fullJSON)
		} else {
			body = extractedBody
		}
	}

	return title, body
}

// HandleGitHubWebhook handles POST /webhook/:device_key/github
func (h *WebhookHandler) HandleGitHubWebhook(c *gin.Context) {
	deviceKey := c.Param("device_key")
	if deviceKey == "" {
		c.JSON(http.StatusBadRequest, model.PushResponse{
			Success: false,
			Error:   "Missing device key",
		})
		return
	}

	device, err := h.storage.GetDeviceByKey(deviceKey)
	if err != nil || device == nil {
		c.JSON(http.StatusNotFound, model.PushResponse{
			Success: false,
			Error:   "Device not found",
		})
		return
	}

	// Read raw body first to allow fallback parsing
	bodyBytes, err := io.ReadAll(c.Request.Body)
	if err != nil {
		h.sendWebhookMessage(deviceKey, device, "GitHub", "Failed to read webhook body", "", c)
		return
	}

	var webhook model.GitHubWebhook
	if err := json.Unmarshal(bodyBytes, &webhook); err != nil {
		h.sendWebhookMessage(deviceKey, device, "GitHub", "Invalid GitHub webhook format", "", c)
		return
	}

	// Parse GitHub event type from header; default to "push" if missing
	eventType := c.GetHeader("X-GitHub-Event")
	if eventType == "" {
		// Infer event type from payload structure
		var raw map[string]interface{}
		if json.Unmarshal(bodyBytes, &raw) == nil {
			if _, hasCommits := raw["commits"]; hasCommits {
				eventType = "push"
			} else if _, hasPR := raw["pull_request"]; hasPR {
				eventType = "pull_request"
			} else if _, hasIssue := raw["issue"]; hasIssue {
				eventType = "issues"
			}
		}
	}

	title := "GitHub"
	formatted := h.formatGitHubWebhook(&webhook, bodyBytes, eventType)

	// Append raw JSON for client "view raw" toggle
	var rawJSON map[string]interface{}
	json.Unmarshal(bodyBytes, &rawJSON)
	fullJSON, _ := json.MarshalIndent(rawJSON, "", "  ")
	body := fmt.Sprintf("%s\n\n--- 完整数据 ---\n%s", formatted, string(fullJSON))

	h.sendWebhookMessage(deviceKey, device, title, body, "", c)
}

// HandleGitLabWebhook handles POST /webhook/:device_key/gitlab
func (h *WebhookHandler) HandleGitLabWebhook(c *gin.Context) {
	deviceKey := c.Param("device_key")
	if deviceKey == "" {
		c.JSON(http.StatusBadRequest, model.PushResponse{
			Success: false,
			Error:   "Missing device key",
		})
		return
	}

	device, err := h.storage.GetDeviceByKey(deviceKey)
	if err != nil || device == nil {
		c.JSON(http.StatusNotFound, model.PushResponse{
			Success: false,
			Error:   "Device not found",
		})
		return
	}

	// Read raw body first
	bodyBytes, err := io.ReadAll(c.Request.Body)
	if err != nil {
		h.sendWebhookMessage(deviceKey, device, "GitLab", "Failed to read webhook body", "", c)
		return
	}

	var webhook model.GitLabWebhook
	if err := json.Unmarshal(bodyBytes, &webhook); err != nil {
		h.sendWebhookMessage(deviceKey, device, "GitLab", "Invalid GitLab webhook format", "", c)
		return
	}

	title := "GitLab"
	formatted := h.formatGitLabWebhook(&webhook)

	// Append raw JSON for client "view raw" toggle
	var rawJSON map[string]interface{}
	json.Unmarshal(bodyBytes, &rawJSON)
	fullJSON, _ := json.MarshalIndent(rawJSON, "", "  ")
	body := fmt.Sprintf("%s\n\n--- 完整数据 ---\n%s", formatted, string(fullJSON))

	h.sendWebhookMessage(deviceKey, device, title, body, "", c)
}

// HandleDockerHubWebhook handles POST /webhook/:device_key/docker
func (h *WebhookHandler) HandleDockerHubWebhook(c *gin.Context) {
	deviceKey := c.Param("device_key")
	if deviceKey == "" {
		c.JSON(http.StatusBadRequest, model.PushResponse{
			Success: false,
			Error:   "Missing device key",
		})
		return
	}

	device, err := h.storage.GetDeviceByKey(deviceKey)
	if err != nil || device == nil {
		c.JSON(http.StatusNotFound, model.PushResponse{
			Success: false,
			Error:   "Device not found",
		})
		return
	}

	// Read raw body first
	bodyBytes, err := io.ReadAll(c.Request.Body)
	if err != nil {
		h.sendWebhookMessage(deviceKey, device, "Docker Hub", "Failed to read webhook body", "", c)
		return
	}

	var webhook model.DockerHubWebhook
	if err := json.Unmarshal(bodyBytes, &webhook); err != nil {
		h.sendWebhookMessage(deviceKey, device, "Docker Hub", "Invalid Docker Hub webhook format", "", c)
		return
	}

	title := "Docker Hub"
	formatted := h.formatDockerHubWebhook(&webhook)

	// Append raw JSON for client "view raw" toggle
	var rawJSON map[string]interface{}
	json.Unmarshal(bodyBytes, &rawJSON)
	fullJSON, _ := json.MarshalIndent(rawJSON, "", "  ")
	body := fmt.Sprintf("%s\n\n--- 完整数据 ---\n%s", formatted, string(fullJSON))

	h.sendWebhookMessage(deviceKey, device, title, body, "", c)
}

// HandleGiteaWebhook handles POST /webhook/:device_key/gitea
func (h *WebhookHandler) HandleGiteaWebhook(c *gin.Context) {
	deviceKey := c.Param("device_key")
	if deviceKey == "" {
		c.JSON(http.StatusBadRequest, model.PushResponse{
			Success: false,
			Error:   "Missing device key",
		})
		return
	}

	device, err := h.storage.GetDeviceByKey(deviceKey)
	if err != nil || device == nil {
		c.JSON(http.StatusNotFound, model.PushResponse{
			Success: false,
			Error:   "Device not found",
		})
		return
	}

	// Read raw body first
	bodyBytes, err := io.ReadAll(c.Request.Body)
	if err != nil {
		h.sendWebhookMessage(deviceKey, device, "Gitea", "Failed to read webhook body", "", c)
		return
	}

	var webhook model.GiteaWebhook
	if err := json.Unmarshal(bodyBytes, &webhook); err != nil {
		h.sendWebhookMessage(deviceKey, device, "Gitea", "Invalid Gitea webhook format", "", c)
		return
	}

	eventType := c.GetHeader("X-Gitea-Event")
	title := "Gitea"
	formatted := h.formatGiteaWebhook(&webhook, eventType)

	// Append raw JSON for client "view raw" toggle
	var rawJSON map[string]interface{}
	json.Unmarshal(bodyBytes, &rawJSON)
	fullJSON, _ := json.MarshalIndent(rawJSON, "", "  ")
	body := fmt.Sprintf("%s\n\n--- 完整数据 ---\n%s", formatted, string(fullJSON))

	h.sendWebhookMessage(deviceKey, device, title, body, "", c)
}

// sendWebhookMessage sends a webhook message to device
func (h *WebhookHandler) sendWebhookMessage(deviceKey string, device *model.Device, title, body, image string, c *gin.Context) {
	messageID := uuid.New().String()

	msg := &model.Message{
		DeviceID:  device.ID,
		MessageID: messageID,
		Title:     title,
		Body:      body,
		Group:     "webhook",
		Image:     image,
	}

	// Encrypt if device has public key
	var encryptedContent string
	if device.PublicKey != "" {
		publicKey, err := h.crypto.ParsePublicKey(device.PublicKey)
		if err == nil {
			payload := map[string]interface{}{
				"title": title,
				"body":  body,
				"group": "webhook",
				"image": image,
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
		Timestamp: 0, // Will be set in hub
		Data: map[string]interface{}{
			"title":             title,
			"body":              body,
			"group":             "webhook",
			"image":             image,
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

// formatGitHubWebhook formats GitHub webhook into readable message
func (h *WebhookHandler) formatGitHubWebhook(w *model.GitHubWebhook, rawBody []byte, eventType string) string {
	var sb strings.Builder

	// Get commit message: prefer head_commit, fallback to commits[0]
	commitMsg := w.HeadCommit.Message
	if commitMsg == "" {
		// Try to extract from commits array in raw JSON
		var raw struct {
			Commits []struct {
				Message string `json:"message"`
				Author  struct {
					Name string `json:"name"`
				} `json:"author"`
			} `json:"commits"`
		}
		if json.Unmarshal(rawBody, &raw) == nil && len(raw.Commits) > 0 {
			commitMsg = raw.Commits[0].Message
		}
	}

	switch eventType {
	case "push":
		branch := strings.TrimPrefix(w.Ref, "refs/heads/")
		sb.WriteString(fmt.Sprintf("【Push】[%s](%s)\n", w.Repository.FullName, w.Repository.HTMLURL))
		sb.WriteString(fmt.Sprintf("分支: %s\n", branch))
		if !w.Forced {
			if commitMsg != "" {
				sb.WriteString(fmt.Sprintf("提交: %s\n", commitMsg))
			}
		} else {
			sb.WriteString("强制推送\n")
		}
		sb.WriteString(fmt.Sprintf("推送者: %s", w.Pusher.Name))
	case "ping":
		sb.WriteString(fmt.Sprintf("【Ping】[%s](%s)\n", w.Repository.FullName, w.Repository.HTMLURL))
	case "":
		sb.WriteString(fmt.Sprintf("【Event】[%s](%s)\n", w.Repository.FullName, w.Repository.HTMLURL))
		if commitMsg != "" {
			sb.WriteString(fmt.Sprintf("提交: %s\n", commitMsg))
		}
		if w.Pusher.Name != "" {
			sb.WriteString(fmt.Sprintf("推送者: %s", w.Pusher.Name))
		}
	default:
		sb.WriteString(fmt.Sprintf("【%s】[%s](%s)\n", eventType, w.Repository.FullName, w.Repository.HTMLURL))
		if commitMsg != "" {
			sb.WriteString(fmt.Sprintf("提交: %s\n", commitMsg))
		}
	}

	return sb.String()
}

// formatGitLabWebhook formats GitLab webhook into readable message
func (h *WebhookHandler) formatGitLabWebhook(w *model.GitLabWebhook) string {
	var sb strings.Builder

	switch w.ObjectKind {
	case "push":
		branch := strings.TrimPrefix(w.Ref, "refs/heads/")
		sb.WriteString(fmt.Sprintf("【Push】[%s](%s)\n", w.Project.Name, w.Project.WebURL))
		sb.WriteString(fmt.Sprintf("分支: %s\n", branch))
		sb.WriteString(fmt.Sprintf("提交: %s\n", w.Commit.Message))
		sb.WriteString(fmt.Sprintf("推送者: %s", w.UserName))
	case "merge_request":
		sb.WriteString(fmt.Sprintf("【Merge Request】[%s](%s)\n", w.Project.Name, w.Project.WebURL))
	default:
		sb.WriteString(fmt.Sprintf("【%s】[%s](%s)\n", w.ObjectKind, w.Project.Name, w.Project.WebURL))
	}

	return sb.String()
}

// formatDockerHubWebhook formats Docker Hub webhook into readable message
func (h *WebhookHandler) formatDockerHubWebhook(w *model.DockerHubWebhook) string {
	var sb strings.Builder

	sb.WriteString(fmt.Sprintf("【Docker Hub】[%s](%s)\n", w.Repository.RepoName, w.Repository.RepoURL))
	sb.WriteString(fmt.Sprintf("标签: %s\n", w.PushData.Tag))
	sb.WriteString(fmt.Sprintf("推送者: %s", w.PushData.Pusher))

	return sb.String()
}

// formatGiteaWebhook formats Gitea webhook into readable message
func (h *WebhookHandler) formatGiteaWebhook(w *model.GiteaWebhook, eventType string) string {
	var sb strings.Builder

	switch eventType {
	case "push":
		branch := strings.TrimPrefix(w.Ref, "refs/heads/")
		sb.WriteString(fmt.Sprintf("【Push】[%s](%s)\n", w.Repository.FullName, w.Repository.HTMLURL))
		sb.WriteString(fmt.Sprintf("分支: %s\n", branch))
		sb.WriteString(fmt.Sprintf("提交: %s\n", w.HeadCommit.Message))
		sb.WriteString(fmt.Sprintf("推送者: %s", w.Pusher.Name))
	default:
		sb.WriteString(fmt.Sprintf("【%s】[%s](%s)\n", eventType, w.Repository.FullName, w.Repository.HTMLURL))
	}

	return sb.String()
}
