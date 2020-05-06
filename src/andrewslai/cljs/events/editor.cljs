(ns andrewslai.cljs.events.editor
  (:require [cljsjs.slate-html-serializer :as shs]
            [re-frame.core :refer [dispatch reg-event-db]]
            [andrewslai.cljs.modal :refer [modal-template close-modal]]
            [reagent.core :as reagent]))

;; HTML Serializer
;; This serializer is used for the following purposes: * serialize slatejs
;; document to HTML string for display and persistence in app-db. * deserialize
;; HTML string to slatejs document to import existing html into editor.
(def html-serializer-rules
  [{:deserialize
    (fn deserialize-all
      [el next]
      (let [el-name (.toLowerCase (.-tagName el))
            child-nodes (.-childNodes el)
            deserialized-child-nodes (next child-nodes)]
        (case el-name
          "em"
          (clj->js {:object "mark"
                    :type "italic"
                    :nodes deserialized-child-nodes})
          "p"
          (clj->js {:object "block"
                    :type "paragraph"
                    :nodes deserialized-child-nodes})
          "strong"
          (clj->js {:object "mark"
                    :type "bold"
                    :nodes deserialized-child-nodes})

          "code"
          (clj->js {:object "block"
                    :type "code"
                    :nodes deserialized-child-nodes})

          js/undefined)))

    :serialize
    (fn serialize-all
      [object children]
      (let [slate-object (.-object object)
            slate-type (.-type object)]
        ;; NOTE: The serializers below need to be kept in sync with render-node
        ;; and render-mark functions!
        (case [slate-object slate-type]
          ["block"  "paragraph"] (reagent/as-element [:p {} children])
          ["mark" "italic"] (reagent/as-element [:em {} children])
          ["mark" "bold"] (reagent/as-element [:strong {} children])
          ["block" "code"] (reagent/as-element [:pre {} [:code children]])
          ["block" "code-inline"] (reagent/as-element [:pre {} [:code children]])
          ["block" "code-block"] (reagent/as-element [:pre {:style {:white-space "pre-wrap"
                                                                    :background "hsl(30,80%,90%)"}}
                                                      [:code children]])
          js/undefined)))}])

(def html-serializer
  (let [Html (-> js/SlateHtmlSerializer
                 (js->clj :keywordize-keys true)
                 :default)]
    (-> {:rules html-serializer-rules}
        clj->js
        Html.)))

(comment
  (->> {:document {:nodes [{:object "block"
                            :type "paragraph"
                            :nodes [{:object "text"
                                     :leaves [{:text "Hello world!"}]}]}]}}
       clj->js
       (.fromJSON js/Slate.Value))

  (.serialize html-serializer
              (->> {:document {:nodes [{:object "block"
                                        :type "paragraph"
                                        :nodes [{:object "text"
                                                 :leaves [{:text "Hello world!"}]}]}]}}
                   clj->js
                   (.fromJSON js/Slate.Value)))
  )

(defn editor-model->clj [model]
  (let [html (.serialize html-serializer model)]
    {:html html}))

(reg-event-db
  :editor-text-changed
  (fn [db [_ new-value]]
    (let [serialized-text (editor-model->clj new-value)]
      #_(println serialized-text)
      (assoc db :editor-data new-value))))

(reg-event-db
  :editor-metadata-changed
  (fn [db [_ new-value]]
    (assoc db :editor-metadata new-value)))

(defn article-failure [payload]
  {:title "Article creation failed!"
   :body [:div {:style {:overflow-wrap "break-word"}}
          [:p [:b "Creation unsuccessful."]]
          [:br]
          [:p (str payload)]
          [:br]]
   :footer [:button {:type "button" :title "Ok"
                     :class "btn btn-default"
                     :on-click #(close-modal)} "Ok"]
   :close-fn close-modal})

(defn article-create-success [{:keys [title author timestamp
                                      article_tags article_url] :as article}]
  (println article (type article))
  (let [url (str "/#/" article_tags "/content/" article_url)
        close-fn (fn []
                   (do (close-modal)
                       (set! (.-href (.-location js/document)) url)))]
    {:title "Successful article creation!"
     :body [:div
            [:br]
            [:div
             [:p [:b "Article: "] title]
             [:p [:b "Author: "] author]
             [:p [:b "Timestamp: "] (str timestamp)]
             [:p [:b "URL: "] url]]]
     :footer [:button {:type "button" :title "Ok"
                       :class "btn btn-default"
                       :on-click close-fn} "Ok"]
     :close-fn close-fn}))

(defn process-create-article [db article]
  (dispatch [:modal {:show? true
                     :child (modal-template (article-create-success article))
                     :size :small}])
  db)

(defn process-unsuccessful-article [db {:keys [response]}]
  (dispatch [:modal {:show? true
                     :child (modal-template (article-failure response))
                     :size :small}])
  db)
