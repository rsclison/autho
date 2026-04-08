(ns autho.auth
  (:require [buddy.auth :as auth]
            [buddy.auth.backends :as backends]
            [buddy.auth.middleware :as middleware])
  (:import (java.security MessageDigest)))

;; --- Secrets ---
;; Secrets are loaded from environment variables for security.
;; Required environment variables:
;; - JWT_SECRET: Secret key for JWT/HS256 signing — must be ≥ 32 characters (256 bits, RGS)
;; - API_KEY: API key for trusted application authentication — must be ≥ 32 characters
(def jwt-secret
  (let [secret (System/getenv "JWT_SECRET")]
    (cond
      (nil? secret)
      (throw (ex-info "JWT_SECRET environment variable must be set"
                      {:type ::missing-config}))
      (< (count secret) 32)
      (throw (ex-info "JWT_SECRET must be at least 32 characters (256 bits) for HS256"
                      {:type ::weak-config :length (count secret) :minimum 32}))
      :else secret)))

(def api-key
  (let [key (System/getenv "API_KEY")]
    (cond
      (nil? key)
      (throw (ex-info "API_KEY environment variable must be set"
                      {:type ::missing-config}))
      (< (count key) 32)
      (throw (ex-info "API_KEY must be at least 32 characters"
                      {:type ::weak-config :length (count key) :minimum 32}))
      :else key)))

;; --- Authentication Backends ---

;; 1. JWT Backend for end-users
;; This backend authenticates requests that have a `Authorization: Token <jwt>` header.
;; Algorithm is explicitly set to HS256 (HMAC-SHA256, approved by RGS Annexe B1).
;; The :options {:alg :hs256} ensures "alg: none" and other algorithms are rejected.
(def jws-backend
  (backends/jws {:secret  jwt-secret
                 :options {:alg :hs256}}))

;; Constant-time comparison to prevent timing attacks on the API key.
;; Uses MessageDigest/isEqual which compares two byte arrays in O(n) regardless of content.
(defn- constant-time-equals? [^String a ^String b]
  (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8")))

;; 2. API Key Backend for trusted applications
;; This backend authenticates requests that have a `X-API-Key: <key>` header.
(def api-key-backend
  (backends/token
    {:authfn (fn [req token]
               (when (constant-time-equals? token api-key)
                 {:auth-method :api-key
                  :client-id :trusted-internal-app}))
     :token-name "X-API-Key"}))

;; --- Middleware ---

;; This middleware combines both authentication backends.
;; `buddy-auth` will try them in order. If the first one fails or does not apply,
;; it will try the next one.
(defn wrap-authentication [handler]
  (middleware/wrap-authentication handler jws-backend api-key-backend))
