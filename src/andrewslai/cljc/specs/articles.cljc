(ns andrewslai.cljc.specs.articles
  (:require
   #?@(:clj  [[clojure.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]]
       :cljs [[cljs.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]])))

(def timestamp (s/or :date spec/inst? :string spec/string?))

(s/def :andrewslai.article/id spec/integer?)
(s/def :andrewslai.article/article-name spec/string?)
(s/def :andrewslai.article/title spec/string?)
(s/def :andrewslai.article/article-tags spec/string?)
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

(s/def :andrewslai.branch/id spec/string?)
(s/def :andrewslai.branch/branch-id spec/string?)
(s/def :andrewslai.branch/article-id spec/string?)
(s/def :andrewslai.branch/published-at timestamp)
(s/def :andrewslai.branch/branch-name spec/string?)
(s/def :andrewslai.branch/created-at timestamp)
(s/def :andrewslai.branch/modified-at timestamp)

(s/def :andrewslai.article/article-branch
  (s/keys :req-un [:andrewslai.branch/branch-name]
          :opt-un [:andrewslai.branch/article-id
                   :andrewslai.branch/published-at
                   :andrewslai.branch/branch-id
                   :andrewslai.article/author
                   :andrewslai.article/article-url
                   :andrewslai.article/article-tags]))

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
