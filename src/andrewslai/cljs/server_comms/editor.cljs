(ns andrewslai.cljs.server-comms.editor
  (:require [ajax.core :refer [DELETE GET PATCH POST]]
            [andrewslai.cljs.modals.editor :refer [create-article-success-modal
                                                   create-article-failure-modal]]
            [re-frame.core :refer [dispatch]]
            [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Create articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn create-article [data headers]
  (POST "/articles/"
      {:params data
       :headers headers
       :format :json
       :handler #(dispatch [:show-modal (create-article-success-modal %)])
       :error-handler #(dispatch [:show-modal (create-article-failure-modal %)])}))
