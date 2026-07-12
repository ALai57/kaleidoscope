(ns kaleidoscope.persistence.scrape-pipeline
  "Persistence for the raw scrape pipeline. Two append-only tables: `raw_scrapes`
  (immutable acquisition corpus) and `processing_runs` (provenance log). JSONB
  columns are handled by the SettableParameter/ReadableColumn extensions in
  `persistence.rdbms`. Persistence only — no HTTP, no domain logic. See
  plans/2026-07-12-raw-scrape-pipeline/DESIGN.md."
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]))

(defn create-raw-scrape!
  "Insert one immutable raw_scrapes row; return the created row (incl. :id).
  Fetch fields are optional — a pre-fetch failure records request-url only."
  [db {:keys [hostname request-url final-url http-status fetch-tier raw-html]}]
  (first (rdbms/insert! db :raw-scrapes
                        {:id          (utils/uuid)
                         :hostname    hostname
                         :request-url request-url
                         :final-url   final-url
                         :http-status http-status
                         :fetch-tier  fetch-tier
                         :raw-html    raw-html
                         :created-at  (utils/now)}
                        :ex-subtype :UnableToCreateRawScrape)))

(defn create-processing-run!
  "Insert one processing_runs row; return the created row (incl. :id). `outcome`
  may be a keyword or string; stored as its name. JSONB fields (`techniques`,
  `facts`, `content`, `llm-calls`, `warnings`, `error-detail`) are passed as
  Clojure data and serialized by the rdbms parameter extensions."
  [db {:keys [hostname raw-scrape-id pipeline-version techniques facts content
              llm-calls warnings outcome error-detail]}]
  (first (rdbms/insert! db :processing-runs
                        {:id               (utils/uuid)
                         :hostname         hostname
                         :raw-scrape-id    raw-scrape-id
                         :pipeline-version pipeline-version
                         :techniques       techniques
                         :facts            facts
                         :content          content
                         :llm-calls        (vec (or llm-calls []))
                         :warnings         (vec (or warnings []))
                         :outcome          (name outcome)
                         :error-detail     error-detail
                         :created-at       (utils/now)}
                        :ex-subtype :UnableToCreateProcessingRun)))

(defn get-raw-scrape
  "One raw scrape by id, scoped to hostname."
  [db id hostname]
  (first (rdbms/find-by-keys db :raw-scrapes {:id id :hostname hostname})))

(defn get-processing-run
  "One processing run by id, scoped to hostname."
  [db id hostname]
  (first (rdbms/find-by-keys db :processing-runs {:id id :hostname hostname})))
