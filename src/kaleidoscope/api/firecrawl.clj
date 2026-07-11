(ns kaleidoscope.api.firecrawl
  "Rendering fetch fallback for pages behind bot protection (e.g. Cloudflare
  interactive challenges) that a direct HTTP fetch cannot pass. Firecrawl runs a
  real browser + residential proxies + challenge solving and returns the
  rendered page. We ask for `rawHtml` (not the cleaned `html`) so the
  schema.org JSON-LD `<script>` blocks survive for the scraper's own
  extraction. See plans/2026-07-11-recipe-scrape-fallback/DESIGN.md."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [taoensso.timbre :as log]))

(def ^:private scrape-endpoint "https://api.firecrawl.dev/v1/scrape")

(defprotocol RecipeFetcher
  (fetch-rendered [this url]
    "Fetch `url` through a rendering service that clears bot protection,
    returning raw HTML. Throws ex-info {:type :scrape :reason :render-failed}
    on failure."))

(defrecord FirecrawlFetcher [api-key]
  RecipeFetcher
  (fetch-rendered [_ url]
    (log/infof "Fetching %s via Firecrawl" url)
    (let [{:keys [status body]} (http/post scrape-endpoint
                                           {:headers            {"Authorization" (str "Bearer " api-key)}
                                            :content-type       :json
                                            :body               (json/encode {:url url :formats ["rawHtml"]})
                                            :throw-exceptions   false
                                            :connection-timeout 15000
                                            :socket-timeout     60000})
          raw-html (when body
                     (-> (try (json/decode body true) (catch Exception _ nil))
                         (get-in [:data :rawHtml])))]
      (if (and (= status 200) raw-html)
        raw-html
        (do (log/errorf "Firecrawl fetch failed: status %s" status)
            (throw (ex-info "Firecrawl fetch failed"
                            {:type :scrape :reason :render-failed :status status})))))))

;; A mock fetcher for local dev / the "mock" launcher: returns canned HTML so a
;; bot-block path can be exercised without a live Firecrawl call. Tests inject
;; their own RecipeFetcher and do not depend on this.
(defrecord MockFetcher [html]
  RecipeFetcher
  (fetch-rendered [_ _url] html))

(defn make-firecrawl-fetcher
  [{:keys [api-key]}]
  (->FirecrawlFetcher api-key))

(defn make-mock-fetcher
  ([] (make-mock-fetcher "<html><body>mock rendered page</body></html>"))
  ([html] (->MockFetcher html)))
