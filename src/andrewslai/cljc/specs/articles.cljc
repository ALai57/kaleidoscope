(ns andrewslai.cljc.specs.articles
  (:require
   #?@(:clj  [[clojure.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]]
       :cljs [[cljs.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]])))

(s/def :andrewslai.article/id spec/integer?)
(s/def :andrewslai.article/article-name spec/string?)
(s/def :andrewslai.article/title spec/string?)
(s/def :andrewslai.article/article-tags spec/string?)
(s/def :andrewslai.article/timestamp (s/or :date spec/inst? :string spec/string?))
(s/def :andrewslai.article/article-url spec/string?)
(s/def :andrewslai.article/author spec/string?)
(s/def :andrewslai.article/content spec/string?)

(s/def :andrewslai.article/article
  (s/keys :req-un [:andrewslai.article/article-tags
                   :andrewslai.article/author
                   :andrewslai.article/article-url]
          :opt-un [:andrewslai.article/id
                   :andrewslai.article/content
                   :andrewslai.article/title]))

(s/def :andrewslai.article/articles
  (s/coll-of :andrewslai.article/article))

(def example-article
  {:id           4
   :article-tags "about",
   :article-url  "my-fourth-article",
   :author       "Andrew Lai",
   :content      "<p>Content from 4</p>"
   :timestamp    "2020-10-28T02:55:27Z",
   :title        "My fourth article"})

(comment
  ;; Example data for article spec

  (gen/sample :andrewslai.article/article)

  (s/valid? :andrewslai.article/article example-article)
  )
