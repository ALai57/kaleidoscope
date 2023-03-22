(ns kaleidoscope.clj.api.portfolio
  (:require [kaleidoscope.clj.persistence.rdbms :as rdbms]
            [clojure.spec.alpha :as s]))

(s/def :kaleidoscope.portfolio/id int?)
(s/def :kaleidoscope.portfolio/name string?)
(s/def :kaleidoscope.portfolio/url string?)
(s/def :kaleidoscope.portfolio/image-url string?)
(s/def :kaleidoscope.portfolio/description string?)
(s/def :kaleidoscope.portfolio/tags string?)

(s/def :kaleidoscope.portfolio/entry
  (s/keys :req-un [:kaleidoscope.portfolio/id
                   :kaleidoscope.portfolio/name
                   :kaleidoscope.portfolio/url
                   :kaleidoscope.portfolio/image-url
                   :kaleidoscope.portfolio/description
                   :kaleidoscope.portfolio/tags]))

(s/def :kaleidoscope.portfolio/entries
  (s/coll-of :kaleidoscope.portfolio/entry))

(s/def :kaleidoscope.portfolio/name-1 :kaleidoscope.portfolio/name)
(s/def :kaleidoscope.portfolio/name-2 :kaleidoscope.portfolio/name)
(s/def :kaleidoscope.portfolio/relation string?)

(s/def :kaleidoscope.portfolio/link
  (s/keys :req-un [:kaleidoscope.portfolio/id
                   :kaleidoscope.portfolio/name-1
                   :kaleidoscope.portfolio/relation
                   :kaleidoscope.portfolio/name-2
                   :kaleidoscope.portfolio/description]))

(s/def :kaleidoscope.portfolio/links
  (s/coll-of :kaleidoscope.portfolio/link))

(s/def :kaleidoscope/portfolio
  (s/keys :req-un [:kaleidoscope.portfolio/nodes
                   :kaleidoscope.portfolio/links]))

(defn portfolio?
  [x]
  (s/valid? :kaleidoscope/portfolio x))

(def get-nodes
  (rdbms/make-finder :portfolio-entries))

(def get-links
  (rdbms/make-finder :portfolio-links))

(defn get-portfolio
  [database]
  {:nodes (get-nodes database)
   :links (get-links database)})
