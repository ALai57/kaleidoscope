(ns andrewslai.clj.api.portfolio
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [clojure.spec.alpha :as s]))

(s/def :andrewslai.portfolio/id int?)
(s/def :andrewslai.portfolio/name string?)
(s/def :andrewslai.portfolio/url string?)
(s/def :andrewslai.portfolio/image-url string?)
(s/def :andrewslai.portfolio/description string?)
(s/def :andrewslai.portfolio/tags string?)

(s/def :andrewslai.portfolio/entry
  (s/keys :req-un [:andrewslai.portfolio/id
                   :andrewslai.portfolio/name
                   :andrewslai.portfolio/url
                   :andrewslai.portfolio/image-url
                   :andrewslai.portfolio/description
                   :andrewslai.portfolio/tags]))

(s/def :andrewslai.portfolio/entries
  (s/coll-of :andrewslai.portfolio/entry))

(s/def :andrewslai.portfolio/name-1 :andrewslai.portfolio/name)
(s/def :andrewslai.portfolio/name-2 :andrewslai.portfolio/name)
(s/def :andrewslai.portfolio/relation string?)

(s/def :andrewslai.portfolio/link
  (s/keys :req-un [:andrewslai.portfolio/id
                   :andrewslai.portfolio/name-1
                   :andrewslai.portfolio/relation
                   :andrewslai.portfolio/name-2
                   :andrewslai.portfolio/description]))

(s/def :andrewslai.portfolio/links
  (s/coll-of :andrewslai.portfolio/link))

(s/def :andrewslai/portfolio
  (s/keys :req-un [:andrewslai.portfolio/nodes
                   :andrewslai.portfolio/links]))

(defn portfolio?
  [x]
  (s/valid? :andrewslai/portfolio x))

(def get-nodes
  (rdbms/make-finder :portfolio-entries))

(def get-links
  (rdbms/make-finder :portfolio-links))

(defn get-portfolio
  [database]
  {:nodes (get-nodes database)
   :links (get-links database)})
