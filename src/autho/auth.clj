(ns autho.auth
  (:require [buddy.auth :as auth]
            [buddy.auth.backends :as backends]
            [buddy.auth.middleware :as middleware]))

;; --- Secrets ---
;; Secrets are loaded from environment variables for security.
;; Required environment variables:
;; - JWT_SECRET: Secret key for JWT token signing/verification
;; - API_KEY: API key for trusted application authentication
(def jwt-secret
  (or (System/getenv "JWT_SECRET")
      (throw (ex-info "JWT_SECRET environment variable must be set"
                      {:type ::missing-config}))))

(def api-key
  (or (System/getenv "API_KEY")
      (throw (ex-info "API_KEY environment variable must be set"
                      {:type ::missing-config}))))

;; --- Authentication Backends ---

;; 1. JWT Backend for end-users
;; This backend authenticates requests that have a `Authorization: Token <jwt>` header.
(def jws-backend
  (backends/jws {:secret jwt-secret}))

;; 2. API Key Backend for trusted applications
;; This backend authenticates requests that have a `X-API-Key: <key>` header.
(def api-key-backend
  (backends/token
    {:authfn (fn [req token]
               (when (= token api-key)
                 ;; The identity for a trusted app can be simple.
                 ;; We'll use this later to differentiate between auth methods.
                 {:auth-method :api-key
                  :client-id :trusted-internal-app}))
     :token-name "X-API-Key"}))

;; --- Middleware ---

;; This middleware combines both authentication backends.
;; `buddy-auth` will try them in order. If the first one fails or does not apply,
;; it will try the next one.
(defn wrap-authentication [handler]
  (middleware/wrap-authentication handler jws-backend api-key-backend))
