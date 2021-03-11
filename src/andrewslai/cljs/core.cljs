(ns andrewslai.cljs.core
  (:require-macros [secretary.core :refer [defroute]])
  (:require [goog.events :as events]
            [reagent.dom :refer [render]]
            [re-frame.core :refer [dispatch dispatch-sync]]
            [secretary.core :as secretary]
            [andrewslai.cljs.events.core] ;; required to make the compiler
            [andrewslai.cljs.events.articles]
            [andrewslai.cljs.events.editor]
            [andrewslai.cljs.events.keycloak]
            [andrewslai.cljs.events.projects-portfolio]
            [andrewslai.cljs.keycloak :as keycloak]
            [andrewslai.cljs.subs]   ;; load them (see docs/App-Structure.md)
            [andrewslai.cljs.views]
            ;;[devtools.core :as devtools]
            [keycloak-js :as keycloak-js])
  (:import [goog History]
           [goog.history EventType]))

(dispatch-sync [:initialize-db])
(dispatch-sync [:initialize-keycloak])
(dispatch-sync [:request-recent-articles])
(dispatch-sync [:request-portfolio-cards])

;; -- Debugging aids ----------------------------------------------------------
;;(devtools/install!)       ;; https://github.com/binaryage/cljs-devtools
(enable-console-print!)   ;; so that println writes to `console.log`

;; -- Routes and History ------------------------------------------------------

(defroute "/" []
  (dispatch [:set-active-panel :home]))
(defroute "/:path" [path]
  (dispatch [:set-active-panel (keyword path)]))
(defroute "/:path/content/:content-name" [path content-name]
  (dispatch [:set-active-panel (keyword path)])
  (dispatch [:request-article content-name]))


(def history
  (doto (History.)
    (events/listen EventType.NAVIGATE
                   (fn [event] (secretary/dispatch! (.-token event))))
    (.setEnabled true)))


;; -- Entry Point -------------------------------------------------------------
;; Within ../../resources/public/index.html you'll see this code
;;    window.onload = function () {
;;      andrewslai.cljs.core.main();
;;    }
;; So this is the entry function that kicks off the app once HTML is loaded

(defn ^:export main
  []
  ;; `andrewslai.cljs.views/app` is the root view for the entire UI.
  (render [andrewslai.cljs.views/app]
          (.getElementById js/document "app")))
