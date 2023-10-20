(ns kaleidoscope.models.articles)

(def GetArticleResponse
  [:map
   [:id            :int]
   [:author        :string]
   [:article-url   :string]
   [:article-title :string]
   [:article-tags  :string]
   [:hostname      :string]
   [:summary       [:maybe :string]]
   [:modified-at   inst?]
   [:created-at    inst?]])

(def GetBranchResponse
  [:map
   [:article-id    :int]
   [:article-title :string]
   [:article-url   :string]
   [:author        :string]
   [:branch-id     :int]
   [:branch-name   :string]
   [:created-at    inst?]
   [:hostname      :string]
   [:summary       [:maybe :string]]
   [:modified-at   inst?]
   [:published-at  [:maybe inst?]]])

(def GetVersionResponse
  [:map
   [:article-id    :int]
   [:article-tags  :string]
   [:article-title :string]
   [:article-url   :string]
   [:author        :string]
   [:branch-id     :int]
   [:branch-name   :string]
   [:content       :string]
   [:created-at    inst?]
   [:hostname      :string]
   [:summary       [:maybe :string]]
   [:modified-at   inst?]
   [:published-at  [:maybe inst?]]])

(def GetCompositionResponse
  [:map
   [:article-id    :int]
   [:article-title :string]
   [:article-url   :string]
   [:article-tags  :string]
   [:author        :string]
   [:branch-id     :int]
   [:branch-name   :string]
   [:created-at    inst?]
   [:content       :string]
   [:hostname      :string]
   [:summary       [:maybe :string]]
   [:modified-at   inst?]
   [:version-id    :int]])

(def CreateVersionRequest
  [:map
   [:article-title {:optional true} :string]
   [:article-tags  {:optional true} :string]
   [:content       :string]])

(def example-article
  {:id            1
   :author        "Andrew Lai"
   :article-url   "my-first-article"
   :article-title "My first article"
   :article-tags  "thoughts"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :hostname      "andrewslai.localhost"})

(def example-article-2
  {:id            2
   :author        "Andrew Lai"
   :article-url   "my-second-article"
   :article-title "My second article"
   :article-tags  "thoughts"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :hostname      "andrewslai.localhost"})

(def example-branch-1
  {:article-id    1
   :author        "Andrew Lai"
   :article-url   "my-first-article"
   :article-title "My first article"
   :article-tags  "thoughts"
   :branch-id     1
   :branch-name   "branch-1"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :published-at  "2022-01-03T00:00:00Z"
   :hostname      "andrewslai.localhost"})

(def example-branch-2
  {:article-id    1
   :author        "Andrew Lai"
   :article-url   "my-first-article"
   :article-title "My first article"
   :article-tags  "thoughts"
   :branch-id     1
   :branch-name   "branch-2"
   :created-at    "2022-01-02T00:00:00Z"
   :modified-at   "2022-01-02T00:00:00Z"
   :published-at  nil
   :hostname      "andrewslai.localhost"})

(def example-version-request
  {:article-title "My first article"
   :article-tags  "thoughts"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :published-at  "2022-01-03T00:00:00Z"
   :content       "<p>Hello there!</p>"})

(def example-version-1
  {:article-id    1
   :author        "Andrew Lai"
   :article-url   "my-first-article"
   :article-title "My first article"
   :article-tags  "thoughts"
   :branch-id     1
   :branch-name   "branch-1"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :published-at  "2022-01-03T00:00:00Z"
   :content       "<p>Hello there!</p>"
   :hostname      "andrewslai.localhost"})

(def example-composition-1
  {:article-id    1
   :author        "Andrew Lai"
   :article-url   "my-first-article"
   :article-title "My first article"
   :article-tags  "thoughts"
   :branch-id     1
   :branch-name   "branch-1"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :published-at  "2022-01-03T00:00:00Z"
   :content       "<p>Hello there!</p>"
   :hostname      "andrewslai.localhost"})

(comment
  ;; Example data for article spec

  (require '[malli.generator :as mg])
  (mg/generate GetArticleResponse)
  )
