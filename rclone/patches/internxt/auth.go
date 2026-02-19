// Package internxt provides authentication handling
package internxt

import (
	"context"
	"crypto/hmac"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base32"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	internxtauth "github.com/internxt/rclone-adapter/auth"
	internxtconfig "github.com/internxt/rclone-adapter/config"
	sdkerrors "github.com/internxt/rclone-adapter/errors"
	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/config/configmap"
	"github.com/rclone/rclone/fs/config/obscure"
	"github.com/rclone/rclone/fs/fshttp"
	"github.com/rclone/rclone/lib/oauthutil"
	"golang.org/x/oauth2"
)

type userInfo struct {
	RootFolderID string
	Bucket       string
	BridgeUser   string
	UserID       string
}

type userInfoConfig struct {
	Token string
}

// getUserInfo fetches user metadata from the refresh endpoint
func getUserInfo(ctx context.Context, cfg *userInfoConfig) (*userInfo, error) {
	// Call the refresh endpoint to get all user metadata
	refreshCfg := internxtconfig.NewDefaultToken(cfg.Token)
	resp, err := internxtauth.RefreshToken(ctx, refreshCfg)
	if err != nil {
		return nil, fmt.Errorf("failed to fetch user info: %w", err)
	}

	if resp.User.Bucket == "" {
		return nil, errors.New("API response missing user.bucket")
	}
	if resp.User.RootFolderID == "" {
		return nil, errors.New("API response missing user.rootFolderId")
	}
	if resp.User.BridgeUser == "" {
		return nil, errors.New("API response missing user.bridgeUser")
	}
	if resp.User.UserID == "" {
		return nil, errors.New("API response missing user.userId")
	}

	info := &userInfo{
		RootFolderID: resp.User.RootFolderID,
		Bucket:       resp.User.Bucket,
		BridgeUser:   resp.User.BridgeUser,
		UserID:       resp.User.UserID,
	}

	fs.Debugf(nil, "User info: rootFolderId=%s, bucket=%s",
		info.RootFolderID, info.Bucket)

	return info, nil
}

// parseJWTExpiry extracts the expiry time from a JWT token string
func parseJWTExpiry(tokenString string) (time.Time, error) {
	parser := jwt.NewParser(jwt.WithoutClaimsValidation())
	token, _, err := parser.ParseUnverified(tokenString, jwt.MapClaims{})
	if err != nil {
		return time.Time{}, fmt.Errorf("failed to parse token: %w", err)
	}

	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok {
		return time.Time{}, errors.New("invalid token claims")
	}

	exp, ok := claims["exp"].(float64)
	if !ok {
		return time.Time{}, errors.New("token missing expiration")
	}

	return time.Unix(int64(exp), 0), nil
}

// jwtToOAuth2Token converts a JWT string to an oauth2.Token with expiry
func jwtToOAuth2Token(jwtString string) (*oauth2.Token, error) {
	expiry, err := parseJWTExpiry(jwtString)
	if err != nil {
		return nil, err
	}

	return &oauth2.Token{
		AccessToken: jwtString,
		TokenType:   "Bearer",
		Expiry:      expiry,
	}, nil
}

// computeBasicAuthHeader creates the BasicAuthHeader for bucket operations
// Following the pattern from SDK's auth/access.go:96-102
func computeBasicAuthHeader(bridgeUser, userID string) string {
	sum := sha256.Sum256([]byte(userID))
	hexPass := hex.EncodeToString(sum[:])
	creds := fmt.Sprintf("%s:%s", bridgeUser, hexPass)
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(creds))
}

// refreshJWTToken refreshes the token using Internxt's refresh endpoint
func refreshJWTToken(ctx context.Context, name string, m configmap.Mapper) error {
	currentToken, err := oauthutil.GetToken(name, m)
	if err != nil {
		return fmt.Errorf("failed to get current token: %w", err)
	}

	cfg := internxtconfig.NewDefaultToken(currentToken.AccessToken)
	resp, err := internxtauth.RefreshToken(ctx, cfg)
	if err != nil {
		return fmt.Errorf("refresh request failed: %w", err)
	}

	if resp.NewToken == "" {
		return errors.New("refresh response missing newToken")
	}

	// Convert JWT to oauth2.Token format
	token, err := jwtToOAuth2Token(resp.NewToken)
	if err != nil {
		return fmt.Errorf("failed to parse refreshed token: %w", err)
	}

	err = oauthutil.PutToken(name, m, token, false)
	if err != nil {
		return fmt.Errorf("failed to save token: %w", err)
	}

	if resp.User.Bucket != "" {
		m.Set("bucket", resp.User.Bucket)
	}

	fs.Debugf(name, "Token refreshed successfully, new expiry: %v", token.Expiry)
	return nil
}

// reLogin performs a full re-login using stored email+password credentials.
// Returns the AccessResponse on success, or an error if 2FA is required or login fails.
func (f *Fs) reLogin(ctx context.Context) (*internxtauth.AccessResponse, error) {
	password, err := obscure.Reveal(f.opt.Pass)
	if err != nil {
		return nil, fmt.Errorf("couldn't decrypt password: %w", err)
	}

	cfg := internxtconfig.NewDefaultToken("")
	cfg.HTTPClient = fshttp.NewClient(ctx)

	loginResp, err := internxtauth.Login(ctx, cfg, f.opt.Email)
	if err != nil {
		return nil, fmt.Errorf("re-login check failed: %w", err)
	}

	twoAuthCode := ""
	if loginResp.TFA {
		if f.opt.TOTPSecret != "" {
			// Decrypt TOTP secret if needed (assuming it is stored obscured like password/mnemonic)
			totpSecret, err := obscure.Reveal(f.opt.TOTPSecret)
			if err != nil {
				return nil, fmt.Errorf("couldn't decrypt TOTP secret: %w", err)
			}

			// Generate TOTP code
			code, err := generateTOTP(totpSecret)
			if err != nil {
				return nil, fmt.Errorf("failed to generate TOTP code: %w", err)
			}
			twoAuthCode = code
			fs.Debugf(f, "Generated TOTP code for 2FA")
		} else {
			return nil, errors.New("account requires 2FA but no totp_secret configured")
		}
	}

	resp, err := internxtauth.DoLogin(ctx, cfg, f.opt.Email, password, twoAuthCode)
	if err != nil {
		return nil, fmt.Errorf("re-login failed: %w", err)
	}

	return resp, nil
}

// refreshOrReLogin tries to refresh the JWT token first; if that fails with 401,
// it falls back to a full re-login using stored credentials.
func (f *Fs) refreshOrReLogin(ctx context.Context) error {
	refreshErr := refreshJWTToken(ctx, f.name, f.m)
	if refreshErr == nil {
		newToken, err := oauthutil.GetToken(f.name, f.m)
		if err != nil {
			return fmt.Errorf("failed to get refreshed token: %w", err)
		}
		f.cfg.Token = newToken.AccessToken
		f.cfg.BasicAuthHeader = computeBasicAuthHeader(f.bridgeUser, f.userID)
		fs.Debugf(f, "Token refresh succeeded")
		return nil
	}

	// Check if it's a 401 Unauthorized error
	isUnauthorized := false
	var httpErr *sdkerrors.HTTPError
	if errors.As(refreshErr, &httpErr) && httpErr.StatusCode() == 401 {
		isUnauthorized = true
	} else {
		// Fallback string check if error wrapped peculiarly
		errStr := refreshErr.Error()
		if strings.Contains(errStr, "401") || strings.Contains(errStr, "Unauthorized") {
			isUnauthorized = true
		}
	}

	if isUnauthorized {
		fs.Debugf(f, "Token refresh failed (%v), attempting re-login with stored credentials", refreshErr)

		resp, err := f.reLogin(ctx)
		if err != nil {
			return fmt.Errorf("re-login fallback failed (original refresh error: %v): %w", refreshErr, err)
		}

		oauthToken, err := jwtToOAuth2Token(resp.NewToken)
		if err != nil {
			return fmt.Errorf("failed to parse re-login token: %w", err)
		}
		err = oauthutil.PutToken(f.name, f.m, oauthToken, true)
		if err != nil {
			return fmt.Errorf("failed to save re-login token: %w", err)
		}

		f.cfg.Token = oauthToken.AccessToken
		f.bridgeUser = resp.User.BridgeUser
		f.userID = resp.User.UserID
		f.cfg.BasicAuthHeader = computeBasicAuthHeader(f.bridgeUser, f.userID)
		f.cfg.Bucket = resp.User.Bucket
		f.cfg.RootFolderID = resp.User.RootFolderID

		fs.Debugf(f, "Re-login succeeded, new token expiry: %v", oauthToken.Expiry)
		return nil
	}

	return fmt.Errorf("refresh failed: %w", refreshErr)
}

// reAuthorize is called after getting 401 from the server.
// It serializes re-auth attempts and uses a circuit-breaker to avoid infinite loops.
func (f *Fs) reAuthorize(ctx context.Context) error {
	f.authMu.Lock()
	defer f.authMu.Unlock()

	if f.authFailed {
		return errors.New("re-authorization permanently failed")
	}

	err := f.refreshOrReLogin(ctx)
	if err != nil {
		// Only mark as failed if it's strictly not retryable?
		// For now, if re-login fails, we are stuck.
		f.authFailed = true
		return err
	}

	return nil
}

// generateTOTP generates a Time-based One-Time Password (TOTP)
// using the given secret (base32 encoded), current time, and defaults (30s period, 6 digits).
// It implements RFC 6238.
func generateTOTP(secret string) (string, error) {
	// Clean up input
	secret = strings.TrimSpace(strings.ToUpper(secret))

	// Decode base32 secret
	// Try standard encoding first, then with no padding
	key, err := base32.StdEncoding.DecodeString(secret)
	if err != nil {
		// Try adding padding if needed or using NoPadding
		key, err = base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(secret)
		if err != nil {
			return "", fmt.Errorf("invalid base32 secret: %v", err)
		}
	}

	// Calculate time step
	period := 30
	t := time.Now().Unix() / int64(period)

	// Pack time step into 8 bytes (big endian)
	buf := make([]byte, 8)
	binary.BigEndian.PutUint64(buf, uint64(t))

	// HMAC-SHA1
	mac := hmac.New(sha1.New, key)
	mac.Write(buf)
	sum := mac.Sum(nil)

	// Dynamic truncation
	offset := sum[len(sum)-1] & 0xf
	binCode := binary.BigEndian.Uint32(sum[offset : offset+4])

	// Remove most significant bit
	binCode &= 0x7fffffff

	// Modulo 10^6
	mod := int(math.Pow10(6))
	otp := int(binCode) % mod

	// Format with 6 digits zero padded
	return fmt.Sprintf("%06d", otp), nil
}
