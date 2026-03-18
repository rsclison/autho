(ns autho.otel
  "OpenTelemetry manual instrumentation for autho.

   Uses only the OTel API (no SDK dependency). At runtime:
   - Without the Java agent: all calls are no-ops (zero overhead).
   - With the Java agent (-javaagent:opentelemetry-javaagent.jar):
     spans are exported to OTLP (Jaeger, Zipkin, Grafana Tempo, etc.)

   Configure exporter via environment variables (when using the agent):
     OTEL_EXPORTER_OTLP_ENDPOINT  = http://localhost:4317
     OTEL_SERVICE_NAME            = autho
     OTEL_TRACES_EXPORTER         = otlp   (or jaeger, zipkin, none)

   Usage:
     (otel/with-span \"authz.isAuthorized\" {:subject-id id :resource-class rc}
       (do-work))"
  (:import (io.opentelemetry.api GlobalOpenTelemetry)
           (io.opentelemetry.api.trace SpanKind StatusCode Tracer)
           (io.opentelemetry.context Context)))

(defn current-tracer
  "Returns the shared OpenTelemetry Tracer (lazily initialized)."
  ^Tracer []
  (.getTracer (GlobalOpenTelemetry/get) "autho" "0.1.0"))

(defn set-attrs!
  "Set span attributes from a Clojure map (string/keyword keys, scalar values)."
  [span attrs]
  (doseq [[k v] attrs]
    (when (some? v)
      (.setAttribute span (name k) (str v)))))

(defmacro with-span
  "Execute body inside a new OpenTelemetry span.
   span-name : string
   attrs     : map of extra attributes added to the span
   On exception: records the exception on the span and sets ERROR status before re-throwing."
  [span-name attrs & body]
  `(let [span# (-> (current-tracer)
                   (.spanBuilder ~span-name)
                   (.setSpanKind SpanKind/INTERNAL)
                   (.startSpan))
         ctx#  (.makeCurrent span#)]
     (set-attrs! span# ~attrs)
     (try
       (let [result# (do ~@body)]
         (.setStatus span# StatusCode/OK)
         result#)
       (catch Exception e#
         (.recordException span# e#)
         (.setStatus span# StatusCode/ERROR (.getMessage e#))
         (throw e#))
       (finally
         (.end span#)
         (.close ctx#)))))

(defmacro with-server-span
  "Like with-span but uses SpanKind/SERVER (for top-level request spans)."
  [span-name attrs & body]
  `(let [span# (-> (current-tracer)
                   (.spanBuilder ~span-name)
                   (.setSpanKind SpanKind/SERVER)
                   (.startSpan))
         ctx#  (.makeCurrent span#)]
     (set-attrs! span# ~attrs)
     (try
       (let [result# (do ~@body)]
         (.setStatus span# StatusCode/OK)
         result#)
       (catch Exception e#
         (.recordException span# e#)
         (.setStatus span# StatusCode/ERROR (.getMessage e#))
         (throw e#))
       (finally
         (.end span#)
         (.close ctx#)))))

(defn record-event!
  "Add a structured event to the current active span (if any)."
  [event-name attrs]
  (let [span (.getSpan (Context/current))]
    (when-not (.isRecording span) nil
      (let [ab (io.opentelemetry.api.common.Attributes/builder)]
        (doseq [[k v] attrs]
          (when (some? v)
            (.put ab (io.opentelemetry.api.common.AttributeKey/stringKey (name k)) (str v))))
        (.addEvent span event-name (.build ab))))))
