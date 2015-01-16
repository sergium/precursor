(defproject pc "0.1.0-SNAPSHOT"
  :description "CircleCI's frontend app"
  :url "https://circleci.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [inflections "0.8.2"]

                 [datascript "0.7.1"]

                 [compojure "1.1.8"]
                 [cheshire "5.2.0"]
                 [clj-time "0.6.0"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [javax.servlet/servlet-api "2.5"]
                 [slingshot "0.10.3"]
                 [hiccup "1.0.4"]
                 [org.clojure/tools.logging "0.2.6"]
                 [log4j "1.2.16"]
                 [log4j/apache-log4j-extras "1.1"]
                 [org.slf4j/slf4j-api "1.6.2"]
                 [org.slf4j/slf4j-log4j12 "1.6.2"]
                 [cider/cider-nrepl "0.8.1"]
                 [clj-http "1.0.0"]
                 [com.datomic/datomic-free "0.9.4899" :exclusions [org.slf4j/slf4j-nop]]

                 [ring/ring "1.2.2"]
                 [ring/ring-anti-forgery "1.0.0"]
                 [http-kit "2.1.18"]
                 [com.taoensso/sente "1.2.0"]
                 [clj-stacktrace "0.2.8"]

                 [schejulure "1.0.1"]

                 [org.clojars.pallix/batik "1.7.0"]
                 ;; TODO: need to replace this with a verison that doesn't require a filesystem
                 [gift "0.1.0"]

                 [com.cemerick/pomegranate "0.3.0"]

                 [crypto-equality "1.0.0"]

                 [fs "0.11.1"]

                 [ankha "0.1.4"]
                 [org.clojure/clojurescript "0.0-2665"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [cljs-ajax "0.2.6"]

                 ;; Use yaks/om for the pattern tag (it's in React,
                 ;; but not Om yet)
                 ;;[om "0.6.4"]

                 [com.facebook/react "0.12.2.1"] ;; include for externs
                 ;; TODO: revert back to sablono once new release is cut
                 [dwwoelfel/sablono "0.3.0-9aace24eaa377c4199f7335bc7dfd3218e391ebf"]
                 [secretary "1.2.1"]
                 [com.andrewmcveigh/cljs-time "0.2.4"]
                 [com.cemerick/url "0.1.1"]
                 [hiccups "0.3.0"]

                 [weasel "0.4.2"] ;; repl
                 [figwheel "0.2.1-SNAPSHOT"] ;; hate using snapshots :/
                 ;; Frontend tests
                 [com.cemerick/clojurescript.test "0.3.0"]]

  :figwheel {:http-server-root "public"
             :server-port 3448
             :css-dirs ["resources/public/css"]}

  :plugins [[lein-cljsbuild "1.0.4"]
            [com.cemerick/austin "0.1.6"]
            [lein-figwheel "0.2.1-SNAPSHOT"]]

  :exclusions [[org.clojure/clojure]
               [org.clojure/clojurescript]
               [org.slf4j/log4j-over-slf4j]]

  :main pc.init

  :jvm-opts ["-Djava.net.preferIPv4Stack=true"
             "-server"
             "-XX:MaxPermSize=256m"
             "-XX:+UseConcMarkSweepGC"
             "-Xss1m"
             "-Xmx1024m"
             "-XX:+CMSClassUnloadingEnabled"
             "-Dfile.encoding=UTF-8"]


  :repl-options {:init-ns pc.repl}

  :clean-targets ^{:protect false} [:target-path "resources/public/cljs/"]

  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src-cljs"
                                       "yaks/om/src"]
                        :compiler {:output-to "resources/public/cljs/out/frontend-dev.js"
                                   :output-dir "resources/public/cljs/out"
                                   :optimizations :none
                                   :source-map "resources/public/cljs/out/sourcemap-dev.js"}}
                       {:id "production"
                        :source-paths ["src-cljs" "yaks/om/src"]
                        :compiler {:pretty-print false
                                   :preamble ["public/js/vendor/react-0.12.2.min.js"]
                                   :output-to "resources/public/cljs/production/frontend.js"
                                   :output-dir "resources/public/cljs/production"
                                   :optimizations :advanced
                                   :externs ["src-cljs/js/react-externs.js"
                                             "src-cljs/js/analytics-externs.js"]
                                   ;; :source-map "resources/public/cljs/production/sourcemap-frontend.js"
                                   }}]})
