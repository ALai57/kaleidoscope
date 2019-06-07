(defproject full-stack-template "0.0.1"
  :description "Template for full stack development in Clojure"
  :dependencies [[cheshire "5.8.1"]
                 [clj-http "3.9.1"]
                 [cljs-http "0.1.46"]
                 [cljs-ajax "0.7.5"]
                 [cljsjs/react "15.2.1-1"]
                 [cljsjs/react-dom "15.2.1-1"]
                 [coreagile/defenv "1.0.2"]
                 [hiccup "1.0.5"]
                 [http-kit "2.3.0"]
                 [metosin/compojure-api "1.1.12"]
                 [org.clojure/clojure "1.9.0" :upgrade false]
                 [org.clojure/clojurescript "1.10.520"]
                 [org.clojure/core.async "0.3.442"
                  :exclusions [org.clojure/tools.reader]]
                 [org.clojure/java.jdbc "0.6.1"]
                 [org.postgresql/postgresql "9.4-1201-jdbc41"]
                 [reagent "0.6.0-rc"]
                 [ring "1.7.1"]
                 [sablono "0.7.4"]]

  :plugins [[lein-figwheel "0.5.16"]]
  
  ;; Used to make this compatible with Java 11
  :managed-dependencies [[org.clojure/core.rrb-vector "0.0.13"]
                         [org.flatland/ordered "1.5.7"]]

  :ring {:handler clj.handler/app
         :init clj.handler/init-routes}
  :aot :all
  :uberjar-name "full-stack-template.jar"
  :main clj.handler
  :figwheel {:ring-handler clj.handler/app
             :css-dirs ["resources/public/css"]}
  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src/cljs"]

                ;; The presence of a :figwheel configuration here
                ;; will cause figwheel to inject the figwheel client
                ;; into your build
                :figwheel {:open-urls ["http://localhost:3449/index.html"]}

                :compiler {:main full_stack_template.example
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/example.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true}}
               ]}
  :profiles {:dev {:dependencies []
                   :plugins [[lein-ancient "0.6.15"]
                             [lein-bikeshed "0.5.2"]
                             [lein-kibit "0.1.6"]
                             [lein-ring "0.12.5"]]}})
