(ns autho.validation
  "Secure input validation and sanitization for authorization requests"
  (:require [clojure.string :as str]
            [clojure.data.json :as json])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.validation"))

;; Validation patterns
(def email-pattern (re-pattern "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"))
(def id-pattern (re-pattern "^[a-zA-Z0-9_\\-:\\.]+$"))
(def operation-pattern (re-pattern "^[a-zA-Z0-9_\\-]+$"))

;; Validation functions
(defn validate-string [value min-length max-length pattern]
  (when-not (string? value)
    (throw (ex-info "Value must be a string" {:type :validation-error})))
  (when (< (count value) min-length)
    (throw (ex-info "String is too short" {:type :validation-error})))
  (when (> (count value) max-length)
    (throw (ex-info "String is too long" {:type :validation-error})))
  (when (and pattern (not (re-matches pattern value)))
    (throw (ex-info "String contains invalid characters" {:type :validation-error})))
  value)

(defn validate-subject [subject]
  (when-not (map? subject)
    (throw (ex-info "Subject must be a map" {:type :validation-error})))
  (when-not (:id subject)
    (throw (ex-info "Subject ID is required" {:type :validation-error})))
  (validate-string (:id subject) 1 256 id-pattern)
  (when-let [email (:email subject)]
    (validate-string email 1 254 email-pattern))
  (.debug logger "Subject validated: {}" (:id subject))
  subject)

(defn validate-resource [resource]
  (when-not (map? resource)
    (throw (ex-info "Resource must be a map" {:type :validation-error})))
  (when-not (:class resource)
    (throw (ex-info "Resource class is required" {:type :validation-error})))
  (validate-string (:class resource) 1 100 nil)
  (when-let [id (:id resource)]
    (validate-string id 1 256 id-pattern))
  (.debug logger "Resource validated: {}" (:class resource))
  resource)

(defn validate-operation [operation]
  (validate-string operation 1 100 operation-pattern)
  operation)

(defn validate-context [context]
  (when (and context (not (map? context)))
    (throw (ex-info "Context must be a map" {:type :validation-error})))
  context)

(defn validate-authorization-request [request]
  (when-not (:subject request)
    (throw (ex-info "Subject is required" {:type :validation-error})))
  (when-not (:resource request)
    (throw (ex-info "Resource is required" {:type :validation-error})))
  (when-not (:operation request)
    (throw (ex-info "Operation is required" {:type :validation-error})))
  (validate-subject (:subject request))
  (validate-resource (:resource request))
  (validate-operation (:operation request))
  (when-let [context (:context request)]
    (validate-context context))
  (.debug logger "Request validated successfully")
  request)

;; Security checks
(defn check-for-injection [input]
  (when (string? input)
    (let [injection-patterns [#"(?i)(union|select|insert|update|delete|drop|create|alter)\s"
                           #"(?i)(<script|javascript:|vbscript:)"
                           #"(?i)(\-\-|;|\/\*|\*\/)"
                           #"(?i)(exec|eval|system|cmd|shell|\|)"]]
      (doseq [pattern injection-patterns]
        (when (re-find pattern input)
          (throw (ex-info "security: Potential injection detected" {:type :security-error})))))))

(defn check-size-limits [entity-type entity]
  (let [max-sizes {:subject 10000 :resource 10000 :context 5000}
        entity-size (count (str entity))]
    (when (> entity-size (get max-sizes entity-type 10000))
      (throw (ex-info "Entity size exceeds limit" {:type :validation-error})))))

;; Public API
(defn validate-and-sanitize-request [request]
  (when-not (and (map? request) (:subject request) (:resource request) (:operation request))
    (throw (ex-info "Invalid request structure" {:status 400})))
  (check-for-injection (str request))
  (check-size-limits :request request)
  (validate-authorization-request request))

(defn validation-error->response [error]
  {:status 400
   :headers {"Content-Type" "application/json"
             "X-Content-Type-Options" "nosniff"
             "X-Frame-Options" "DENY"
             "X-XSS-Protection" "1; mode=block"}
   :body (json/write-str {:error "validation-error"
                          :message (or (:details (ex-data error)) "Invalid request")
                          :timestamp (str (java.time.Instant/now))})})

(defn validate-policy-rule [policy]
  (when-not (map? policy)
    (throw (ex-info "Policy must be a map" {:type :validation-error})))
  (when-not (:name policy)
    (throw (ex-info "Policy name is required" {:type :validation-error})))
  (when-not (:resourceClass policy)
    (throw (ex-info "Policy resourceClass is required" {:type :validation-error})))
  (when-not (:operation policy)
    (throw (ex-info "Policy operation is required" {:type :validation-error})))
  (when-not (contains? #{"allow" "deny"} (:effect policy))
    (throw (ex-info "Policy effect must be 'allow' or 'deny'" {:type :validation-error})))
  (.debug logger "Policy validated: {}" (:name policy))
  policy)
