(ns kaleidoscope.models.recipes)

;; The single recipe-content value shape. Both the current recipe (`content`)
;; and the immutable scrape (`original-content`) validate against this, so they
;; cannot drift.
(def RecipeContent
  [:map
   [:title             :string]
   [:ingredients       [:sequential :string]] ;; freeform lines, e.g. "2 cups flour"
   [:instructions-html {:optional true} :string] ;; HTML (TipTap)
   [:servings          {:optional true} [:maybe :string]]
   [:prep-time-minutes {:optional true} [:maybe :int]]
   [:cook-time-minutes {:optional true} [:maybe :int]]])

(def RecipeLabel
  [:map
   [:id         :uuid]
   [:name       :string]
   [:group-id   {:optional true} [:maybe :uuid]]
   [:group-name {:optional true} [:maybe :string]]])

(def GetRecipeResponse
  [:map
   [:id                :uuid]
   [:recipe-url        :string]
   [:hostname          :string]
   [:content           RecipeContent]
   [:original-content  {:optional true} [:maybe RecipeContent]]
   [:labels            {:optional true} [:sequential RecipeLabel]]
   [:source-url        {:optional true} [:maybe :string]]
   [:author            {:optional true} [:maybe :string]]
   [:public-visibility :boolean]
   [:created-at        some?]
   [:modified-at       some?]])

(def CreateRecipeRequest
  [:map
   [:content          RecipeContent]
   [:original-content {:optional true} [:maybe RecipeContent]]
   [:recipe-url       {:optional true} :string]
   [:source-url       {:optional true} [:maybe :string]]
   [:label-ids        {:optional true} [:sequential :uuid]]
   [:public-visibility {:optional true} :boolean]])

(def UpdateRecipeRequest
  [:map
   [:content           {:optional true} RecipeContent]
   [:source-url        {:optional true} [:maybe :string]]
   [:label-ids         {:optional true} [:sequential :uuid]]
   [:public-visibility {:optional true} :boolean]])

(def ScrapeResult
  [:map
   [:recipe            RecipeContent]
   [:suggested-labels  [:sequential :string]]
   [:extraction-method [:enum "json-ld" "llm"]]
   [:warnings          [:sequential :string]]])
