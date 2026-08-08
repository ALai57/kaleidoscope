(ns kaleidoscope.api.curation.providers
  (:require [clojure.string :as str]
            [kaleidoscope.api.curation.http :as http]
            [kaleidoscope.api.curation.anthropic-web :as anthropic-web]
            [kaleidoscope.api.curation.firecrawl :as firecrawl]
            [kaleidoscope.api.curation.mock :as mock]
            [kaleidoscope.api.curation.parse.youtube :as youtube]
            [kaleidoscope.api.curation.parse.goodreads :as goodreads]
            [kaleidoscope.api.curation.parse.readability :as readability])
  (:import [java.net URI]))

(def fetchers
  {:http      #'http/fetch
   :anthropic #'anthropic-web/fetch
   :firecrawl #'firecrawl/fetch
   :mock      #'mock/fetch})

(defn fetch
  "Try each fetcher named in :fetch-policy in order; first :ok wins. Failure is data."
  [ref {:keys [fetch-policy] :as config}]
  (or (->> fetch-policy
           (map (fn [k] ((get fetchers k) ref config)))
           (some (fn [{:keys [status] :as r}] (when (= :ok status) r))))
      {:status :dead :ref ref}))

(defn host-of
  "Extract and normalize the host from a URL string: lower-cased, leading \"www.\"
  stripped. Returns nil for a blank or unparseable url."
  [url]
  (when-not (str/blank? url)
    (try
      (some-> (URI/create url) .getHost str/lower-case
              (as-> h (cond-> h (str/starts-with? h "www.") (subs 4))))
      (catch Exception _ nil))))

(defmulti parse
  "Turn a Fetched map into a Content map, dispatching on the host of :final-url.
  Each parser namespace (parse/youtube, parse/goodreads, parse/readability) owns
  the extraction logic for its host; this multimethod just wires host -> parser."
  (fn [fetched] (host-of (:final-url fetched))))

(defmethod parse "youtube.com" [fetched] (youtube/parse-content fetched))
(defmethod parse "youtu.be" [fetched] (youtube/parse-content fetched))
(defmethod parse "m.youtube.com" [fetched] (youtube/parse-content fetched))

(defmethod parse "goodreads.com" [fetched] (goodreads/parse-content fetched))

(defmethod parse :default [fetched] (readability/parse-content fetched))
