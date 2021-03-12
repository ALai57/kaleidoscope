(ns andrewslai.cljc.specs.articles
  (:require
   #?@(:clj  [[clojure.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]]
       :cljs [[cljs.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]])))

(s/def :andrewslai.article/article_id spec/integer?)
(s/def :andrewslai.article/article_name spec/string?)
(s/def :andrewslai.article/title spec/string?)
(s/def :andrewslai.article/article_tags spec/string?)
(s/def :andrewslai.article/timestamp (s/or :date spec/inst? :string spec/string?))
(s/def :andrewslai.article/article_url spec/string?)
(s/def :andrewslai.article/author spec/string?)
(s/def :andrewslai.article/content spec/string?)

(s/def :andrewslai.article/article
  (s/keys :req-un [:andrewslai.article/title
                   :andrewslai.article/article_tags
                   :andrewslai.article/author
                   :andrewslai.article/timestamp
                   :andrewslai.article/article_url
                   :andrewslai.article/article_id]
          :opt-un [:andrewslai.article/content]))

(s/def :andrewslai.article/articles
  (s/coll-of :andrewslai.article/article))

(def example-article
  {:article_id    4
   :article_tags "about",
   :article_url  "my-fourth-article",
   :author       "Andrew Lai",
   :content      "<p>Content from 4</p>"
   :timestamp    "2020-10-28T02:55:27Z",
   :title        "My fourth article"})

(comment
  ;; Example data for article spec

  (gen/sample :andrewslai.article/article)

  (s/valid? :andrewslai.article/article example-article)
  )
