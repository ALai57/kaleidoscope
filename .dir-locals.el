
((nil
  ;; Start CIDER REPL with the following aliases.
  ;;   :test  - Tests can run at REPL
  ;;   :dev   - Developer tooling included in REPL
  ;;   :build - Build can be run at REPL
  (cider-clojure-cli-global-options . "-A:test:dev:build")
  ))
