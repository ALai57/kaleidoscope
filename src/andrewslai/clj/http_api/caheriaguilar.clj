(ns andrewslai.clj.http-api.caheriaguilar
  (:require [andrewslai.clj.api.authorization :as auth]
            [andrewslai.clj.http-api.andrewslai :as andrewslai]))

(def public-access
  (constantly true))

(def CAHERIAGUILAR-ACCESS-CONTROL-LIST
  [{:pattern #"^/admin.*"        :handler (partial auth/require-role "caheriaguilar")}
   {:pattern #"^/articles.*"     :handler (partial auth/require-role "caheriaguilar")}
   {:pattern #"^/branches.*"     :handler (partial auth/require-role "caheriaguilar")}
   {:pattern #"^/compositions.*" :handler public-access}
   {:pattern #"^/$"              :handler public-access}
   {:pattern #"^/index.html$"    :handler public-access}
   {:pattern #"^/ping"           :handler public-access}

   {:pattern #"^/groups.*"       :handler (partial auth/require-role "caheriaguilar")}

   {:pattern #"^/media.*" :request-method :post :handler (partial auth/require-role "caheriaguilar")}
   {:pattern #"^/media.*" :request-method :get  :handler public-access}

   #_{:pattern #"^/.*" :handler (constantly false)}])

(def caheriaguilar-app
  andrewslai/andrewslai-app)