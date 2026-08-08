(ns kaleidoscope.api.curation.firecrawl
  "Rendering-fetch fetcher for the curation pipeline, backed by the existing
  Firecrawl client (kaleidoscope.api.firecrawl) used elsewhere for bot-blocked
  recipe scraping. `fetch-rendered` throws ex-info on failure — never let
  that escape the fetch-policy fallthrough in providers.clj."
  (:require [kaleidoscope.api.firecrawl :as firecrawl]))

(defn fetch
  "Fetch `ref`'s :url via Firecrawl's rendering service. Returns {:status :ok
  :final-url :raw :content-type :fetcher} on success, {:status :error} on
  any thrown ex-info or exception."
  [ref config]
  (try
    (let [fetcher (firecrawl/make-firecrawl-fetcher {:api-key (:firecrawl-api-key config)})
          html    (firecrawl/fetch-rendered fetcher (:url ref))]
      {:status :ok :final-url (:url ref) :raw html
       :content-type "text/html" :fetcher :firecrawl})
    (catch Exception _
      {:status :error})))
