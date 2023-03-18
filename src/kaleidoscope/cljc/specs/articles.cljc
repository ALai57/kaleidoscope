(ns kaleidoscope.cljc.specs.articles
  (:require
   #?@(:clj  [[clojure.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]]
       :cljs [[cljs.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]])))

(def timestamp (s/or :date spec/inst? :string spec/string?))

(s/def :kaleidoscope.article/id spec/integer?)
(s/def :kaleidoscope.article/article-name spec/string?)
(s/def :kaleidoscope.article/title spec/string?)
(s/def :kaleidoscope.article/article-tags spec/string?)
(s/def :kaleidoscope.article/article-url spec/string?)
(s/def :kaleidoscope.article/author spec/string?)
(s/def :kaleidoscope.article/content spec/string?)

(s/def :kaleidoscope.article/article
  (s/keys :req-un [:kaleidoscope.article/article-tags
                   :kaleidoscope.article/author
                   :kaleidoscope.article/article-url]
          :opt-un [:kaleidoscope.article/id
                   :kaleidoscope.article/content
                   :kaleidoscope.article/title]))

(s/def :kaleidoscope.branch/id spec/string?)
(s/def :kaleidoscope.branch/branch-id spec/string?)
(s/def :kaleidoscope.branch/article-id spec/string?)
(s/def :kaleidoscope.branch/published-at timestamp)
(s/def :kaleidoscope.branch/branch-name spec/string?)
(s/def :kaleidoscope.branch/created-at timestamp)
(s/def :kaleidoscope.branch/modified-at timestamp)

(s/def :kaleidoscope.article/article-branch
  (s/keys :req-un [:kaleidoscope.branch/branch-name]
          :opt-un [:kaleidoscope.branch/article-id
                   :kaleidoscope.branch/published-at
                   :kaleidoscope.branch/branch-id
                   :kaleidoscope.article/author
                   :kaleidoscope.article/article-url
                   :kaleidoscope.article/article-tags]))

(s/def :kaleidoscope.article/articles
  (s/coll-of :kaleidoscope.article/article))

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

  (gen/sample :kaleidoscope.article/article)

  (s/valid? :kaleidoscope.article/article example-article)
  )
