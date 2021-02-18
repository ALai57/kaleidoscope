(ns andrewslai.cljs.core
  (:require-macros [secretary.core :refer [defroute]])
  (:require [goog.events :as events]
            [reagent.dom :refer [render]]
            [re-frame.core :refer [dispatch dispatch-sync]]
            [secretary.core :as secretary]
            [andrewslai.cljs.server-comms.articles :as article-comms]
            [andrewslai.cljs.server-comms.projects-portfolio :as portfolio-comms]
            [andrewslai.cljs.events.core] ;; required to make the compiler
            [andrewslai.cljs.events.articles]
            [andrewslai.cljs.events.users]
            [andrewslai.cljs.events.editor]
            [andrewslai.cljs.events.projects-portfolio]
            [andrewslai.cljs.keycloak :as keycloak]
            [andrewslai.cljs.subs]   ;; load them (see docs/App-Structure.md)
            [andrewslai.cljs.views]
            ;;[devtools.core :as devtools]
            [keycloak-js :as keycloak-js])
  (:import [goog History]
           [goog.history EventType]))


(dispatch-sync [:initialize-db])
(portfolio-comms/get-portfolio-cards)
(article-comms/get-articles 5)

(keycloak/initialize! keycloak/keycloak
                      (fn [auth?]
                        (js/console.log "Authenticated? " auth?)
                        (when auth?
                          (set! js/parent.location.hash "/home")))
                      (fn [& args]))
(js/console.log "***** Keycloak ****" keycloak/keycloak)

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
  (article-comms/get-article content-name))


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
