(ns autho.api.pagination
  "Pagination utilities for API list endpoints.
   Provides parsing and processing of pagination parameters."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def max-per-page 100)
(def default-per-page 20)

;; =============================================================================
;; Parameter Parsing
;; =============================================================================

(defn parse-int-safe
  "Safely parse a string to integer, returning nil on failure."
  [s]
  (try
    (when (and s (not (str/blank? s)))
      (Integer/parseInt (str/trim s)))
    (catch NumberFormatException _
      nil)
    (catch NullPointerException _
      nil)))

(defn parse-pagination-params
  "Parse pagination parameters from request params.
   Returns map with :page, :per-page, :sort, :order, :filter"
  [params]
  (let [order-str (get params "order")]
    {:page (or (parse-int-safe (get params "page")) 1)
     :per-page (min (or (parse-int-safe (get params "per-page")) default-per-page)
                     max-per-page)
     :sort (get params "sort")
     :order (if order-str
              (if (#{"asc" "desc" "ASC" "DESC"} order-str)
                (keyword (str/lower-case order-str))
                :asc)
              :asc)
     :filter (get params "filter")}))

;; =============================================================================
;; Data Processing
;; =============================================================================

(defn apply-filter
  "Filter a collection based on filter parameter.
   Filter parameter is a string like 'key=value,key2=value2'.
   Supports nested keys with dot notation."
  [coll filter-param]
  (if (and filter-param (not (str/blank? filter-param)))
    (let [pairs (str/split filter-param #",")
          filter-map (into {}
                           (map (fn [pair]
                                  (let [[k v] (str/split pair #"=" 2)]
                                    [(keyword k) v]))
                                pairs))]
      (filter (fn [item]
                (every? (fn [[k v]]
                         (let [keys (str/split (name k) #"\.")
                               value (get-in item (map keyword keys))]
                           (= value v)))
                       filter-map))
              coll))
    coll))

(defn apply-sort
  "Sort a collection based on sort key and order.
   Supports nested keys with dot notation."
  [coll sort-key order]
  (if sort-key
    (let [keys (str/split (name sort-key) #"\.")
          get-value (fn [item]
                      (get-in item (map keyword keys)))
          comparator (fn [a b]
                       (let [a-val (get-value a)
                             b-val (get-value b)]
                         (cond
                           (and a-val b-val) (compare a-val b-val)
                           a-val -1  ; a has value, b doesn't - a comes first
                           b-val 1   ; b has value, a doesn't - b comes first
                           :else 0)))]
      (sort-by identity
               (if (= order :desc)
                 #(compare (comparator %2) (comparator %1))
                 comparator)
               coll))
    coll))

(defn paginate
  "Apply pagination to a collection.
   Parameters:
   - coll: Collection to paginate
   - page-params: Map from parse-pagination-params
   Returns map with :items, :page, :per-page, :total"
  [coll page-params]
  (let [filtered (apply-filter coll (:filter page-params))
        sorted (apply-sort filtered (:sort page-params) (:order page-params))
        total (count sorted)
        page (:page page-params)
        per-page (:per-page page-params)
        start (* (dec page) per-page)
        end (+ start per-page)
        items (take per-page (drop start sorted))]
    {:items items
     :page page
     :per-page per-page
     :total total
     :total-pages (max 1 (int (Math/ceil (/ total per-page))))}))

(defn build-links
  "Build HATEOAS links for paginated response.
   base-path should be like '/v1/policies'"
  [base-path page per-page total-pages]
  {:self (str base-path "?page=" page "&per-page=" per-page)
   :next (when (< page total-pages)
           (str base-path "?page=" (inc page) "&per-page=" per-page))
   :prev (when (> page 1)
           (str base-path "?page=" (dec page) "&per-page=" per-page))
   :first (str base-path "?page=1&per-page=" per-page)
   :last (str base-path "?page=" total-pages "&per-page=" per-page)})
