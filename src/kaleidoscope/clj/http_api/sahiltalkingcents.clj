(ns kaleidoscope.clj.http-api.sahiltalkingcents
  (:require [kaleidoscope.clj.api.authorization :as auth]
            [kaleidoscope.clj.http-api.andrewslai :as andrewslai]))

(def public-access
  (constantly true))

(def SAHILTALKINGCENTS-ACCESS-CONTROL-LIST
  [{:pattern #"^/admin.*"        :handler (partial auth/require-role "sahiltalkingcents")}
   {:pattern #"^/articles.*"     :handler (partial auth/require-role "sahiltalkingcents")}
   {:pattern #"^/branches.*"     :handler (partial auth/require-role "sahiltalkingcents")}
   {:pattern #"^/compositions.*" :handler public-access}
   {:pattern #"^/$"              :handler public-access}
   {:pattern #"^/index.html$"    :handler public-access}
   {:pattern #"^/ping"           :handler public-access}

   {:pattern #"^/groups.*"       :handler (partial auth/require-role "sahiltalkingcents")}

   {:pattern #"^/media.*" :request-method :post :handler (partial auth/require-role "sahiltalkingcents")}
   {:pattern #"^/media.*" :request-method :get  :handler public-access}

   #_{:pattern #"^/.*" :handler (constantly false)}])

(def sahiltalkingcents-app
  andrewslai/andrewslai-app)
