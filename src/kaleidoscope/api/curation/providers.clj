(ns kaleidoscope.api.curation.providers
  (:require [kaleidoscope.api.curation.http :as http]
            [kaleidoscope.api.curation.anthropic-web :as anthropic-web]
            [kaleidoscope.api.curation.firecrawl :as firecrawl]
            [kaleidoscope.api.curation.mock :as mock]))

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
