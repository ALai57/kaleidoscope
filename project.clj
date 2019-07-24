(defproject andrewslai "0.0.1"
  :description "Template for full stack development in Clojure"
  :dependencies [[cheshire "5.8.1"]
                 [clj-http "3.9.1"]
                 [coreagile/defenv "1.0.2"]
                 [hiccup "1.0.5"]
                 [http-kit "2.3.0"]
                 [metosin/compojure-api "1.1.12"]
                 [org.clojure/clojure "1.9.0" :upgrade false]
                 [org.clojure/core.async "0.3.442"
                  :exclusions [org.clojure/tools.reader]]
                 [org.clojure/java.jdbc "0.6.1"]
                 [org.postgresql/postgresql "9.4-1201-jdbc41"]

                 [cljs-http "0.1.46"]
                 [cljs-ajax "0.7.5"]
                 [cljsjs/react-bootstrap "1.0.0-beta.9-0"] ;; latest release
                 [cljsjs/react "16.8.6-0"]
                 [cljsjs/react-dom "16.8.6-0"]
                 [org.clojure/clojurescript "1.10.520"]

                 ;; TODO: clean up for advanced opt.
                 [clj-commons/secretary "1.2.4"]
                 [day8.re-frame/tracing "0.5.1"]
                 [reagent "0.8.1"]
                 [re-frame "0.10.5"]
                 [ring "1.7.1"]
                 [sablono "0.7.4"]
                 ]

  :plugins [[lein-figwheel "0.5.18"]
            [lein-cljsbuild "1.1.7"]]

  ;; Used to make this compatible with Java 11
  :managed-dependencies [[org.clojure/core.rrb-vector "0.0.13"]
                         [org.flatland/ordered "1.5.7"]]

  :ring {:handler andrewslai.clj.handler/app
         :init andrewslai.clj.handler/init-routes}
  :aot :all
  :uberjar-name "andrewslai.jar"
  :main andrewslai.clj.handler
  :figwheel {:ring-handler andrewslai.clj.handler/app
             :css-dirs ["resources/public/css"]}
  :clean-targets ^{:protect false} ["./resources/public/js/complied"
                                    "./target"]

  :cljsbuild
  {:builds
   {:dev {:source-paths ["src/andrewslai/cljs"]
          :figwheel {:open-urls ["http://localhost:3449/"]
                     :on-jsload "andrewslai.cljs.core/main"}
          :compiler {:main andrewslai.cljs.core
                     :asset-path "js/compiled/out_andrewslai"
                     :optimizations :none
                     :output-to "resources/public/js/compiled/andrewslai.js"
                     :output-dir "resources/public/js/compiled/out_andrewslai"
                     :npm-deps {:react-spinners "0.4.8"
                                :react "16.8.6"
                                :emotion "10.0.9"}
                     :install-deps true
                     :source-map true
                     :source-map-timestamp true}}
    :prod {:source-paths ["src/andrewslai/cljs"]
           :compiler {:main andrewslai.cljs.core
                      :asset-path "js/compiled/out_andrewslai"
                      :optimizations :advanced
                      :output-to "resources/public/js/compiled/andrewslai.js"
                      :output-dir "resources/public/js/compiled/out_prod"
                      :npm-deps {:react-spinners "0.4.8"
                                 :react "16.8.6"
                                 :emotion "10.0.9"}
                      :install-deps true
                      :source-map-timestamp true}}}

   }
  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.10"]
                                  [figwheel-sidecar "0.5.18"]
                                  [cider/piggieback "0.4.1"]]
                   :source-paths ["src/andrewslai/cljs"]
                   :repl-options {:nrepl-middleware
                                  [cider.piggieback/wrap-cljs-repl]}
                   :plugins [[lein-ancient "0.6.15"]
                             [lein-bikeshed "0.5.2"]
                             [lein-kibit "0.1.6"]
                             [lein-ring "0.12.5"]]}
             :prod {:source-paths ["src/andrewslai/cljs"]
                    :plugins [[lein-ancient "0.6.15"]
                              [lein-bikeshed "0.5.2"]
                              [lein-kibit "0.1.6"]
                              [lein-ring "0.12.5"]]}})
