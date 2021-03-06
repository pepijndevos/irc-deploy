(defproject irc-deploy "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [com.palletops/pallet "0.8.0-RC.1"]
                 [com.palletops/pallet-vmfest "0.3.0-alpha.5"]
                 [org.clojars.tbatchelli/vboxjxpcom "4.2.4"]
                 [com.palletops/upstart-crate "0.8.0-alpha.2"]
                 [ch.qos.logback/logback-classic "1.0.9"]]
  :plugins [[com.palletops/pallet-lein "0.6.0-beta.9"]]
  :aliases {"prod" ["pallet" "up" "--service" "VPS" "--roles" "prod"]
            "dev" ["pallet" "up" "--service" "vbox" "--roles" "dev"]}
  :repositories {"sonatype" "http://oss.sonatype.org/content/repositories/releases"}
  :profiles {:leiningen/reply {:dependencies [[org.slf4j/jcl-over-slf4j "1.7.2"]] :exclusions [commons-logging]}})
