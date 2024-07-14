# IntelliJ Development

## Flowstorm
Flowstorm is a Clojure debugger tool. It can be extremely useful for stepping through code and watching the program's state as it executes.

To set up Flowstorm in IntelliJ, you need to configure a custom REPL: you'll want to configure:
- an nREPL
- with deps.edn, and the following arguments `-A:dev:test`, so that you get both development and test deps and can
  run your tests at the REPL as well

Once you've started the REPL, manually start Flow-storm with the following command in the REPL 
```clojure
(flow-storm.api/local-connect)
```

Then, you can navigate to the "Flows" tab and start recording