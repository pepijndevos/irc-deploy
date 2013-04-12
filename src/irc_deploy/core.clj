(ns irc-deploy.core
  (:use [pallet.api :only [group-spec node-spec]]
        [pallet.crate :only [defplan target-name]]
        [pallet.crate.automated-admin-user :only [automated-admin-user]]
        [pallet.actions :only [package package-manager package-source service with-service-restart service-script exec-script user group remote-file file directory remote-directory]]
        [pallet.stevedore :only [chain-commands]]
        [pallet.config-file.format :only [name-values sectioned-properties]]
        [pallet.script.lib :only [heredoc]]))

(defplan nodejs []
  (package-source "nodejs" :aptitude {:url "ppa:chris-lea/node.js"})
  (package "nodejs"))

(defplan install-kiwi []
  (nodejs)
  (remote-directory
    "/var/lib/kiwi"
    :url "https://github.com/pepijndevos/KiwiIRC/archive/development.tar.gz"
    :owner "kiwi"
    :group "kiwi")
  (exec-script "openssl req -new -newkey rsa:4096 -days 365 -nodes -x509 -subj \"/C=NL/ST=Gelderland/L=Loenen/O=Wishful Coding/CN=*.teamrelaychat.nl\" -keyout /var/lib/kiwi/server/node.key  -out /var/lib/kiwi/server/node.cert"
               "cd /var/lib/kiwi/"
               "npm install"))

(defplan kiwi-conf []
  (group "kiwi" :action :create)
  (user "kiwi"
        :action :create
        :home "/var/lib/kiwi"
        :create-home true
        :system true
        :group "kiwi"))

(defplan start-kiwi []
  (service-script "kiwiirc"
                  :service-impl :upstart
                  :local-file "resources/kiwi.upstart")
  (service "kiwiirc"
           :action :restart
           ;:if-stopped true
           :service-impl :upstart))

(defplan kiwi []
  (kiwi-conf)
  (install-kiwi)
  (start-kiwi))

(defplan ngircd-conf []
  (remote-file
    "/etc/ngircd/ngircd.conf"
    :owner "irc"
    :group "irc"
    :content (sectioned-properties
               {:global  {:Listen "127.0.0.1"
                          :Name (target-name)
                          ;Keep this setting in sync with PIDFILE in /etc/init.d/ngircd
                          :PidFile "/var/run/ircd/ngircd.pid"
                          ;Keep this setting in sync with DAEMONUSER in /etc/init.d/ngircd
                          :ServerGID "irc"
                          ;Keep this setting in sync with DAEMONUSER in /etc/init.d/ngircd
                          :ServerUID "irc"
                          ;Not required by server but by RFC!
                          :AdminInfo1 "A private server"
                          :AdminInfo2 "The Internet"
                          :AdminEMail "support@teamrelaychat.nl"}
                :Options {:PAM "no"
                          :SyslogFacility "local1"}
                :Limits  {:MaxConnectionsIP 0}})))

(defplan ngircd []
  (package-source "ngircd" :aptitude {:url "http://debian.barton.de/debian"
                                      :release "lenny"
                                      :key-url "http://debian.barton.de/debian/archive-key.gpg"})
  (package "ngircd")
  (ngircd-conf)
  (service "ngircd" :action :restart))

(defplan start-znc []
  (service-script "znc"
                  :service-impl :upstart
                  :local-file "resources/znc.upstart")
  (service "znc"
           :action :restart
           :service-impl :upstart))

(defplan znc-conf []
  (group "znc" :action :create)
  (user "znc"
        :action :create
        :create-home true
        :home "/var/lib/znc"
        :system true
        :group "znc")
  (directory "/var/lib/znc/configs/"
             :owner "znc"
             :group "znc")
  (exec-script
    (when-not (file-exists? "/var/lib/znc/configs/znc.conf")
      (heredoc "/var/lib/znc/configs/znc.conf"
               ~(slurp "resources/znc.conf")
               {:literal true})))
  (file "/var/lib/znc/configs/znc.conf" :owner "znc" :group "znc")
  (exec-script "znc --makepem --datadir /var/lib/znc/"))
              

(defplan znc []
  (package "znc")
  (znc-conf)
  (start-znc))

(defplan install-hubot []
  (group "hubot" :action :create)
  (user "hubot"
        :action :create
        :create-home true
        :home "/var/lib/hubot"
        :system true
        :group "hubot")
  (exec-script (if-not (directory? "/var/lib/hubot")
                 (do
                   "npm install -g coffee-script"
                   "npm install -g hubot"
                   "hubot -c /var/lib/hubot"
                   "cd /var/lib/hubot"
                   "chmod +x /var/lib/hubot/bin/hubot"
                   "npm install hubot-irc --save"
                   "npm install")
                 (do
                   "cd /var/lib/hubot"
                   "npm update"))))


(defplan hubot-conf []
  (remote-file "/var/lib/hubot/hubot-scripts.json"
               :owner "hubot"
               :group "hubot"
               :overwrite-changes true
               :local-file "resources/hubot-scripts.json"))

(defplan start-hubot []
  (service-script "hubot"
                  :service-impl :upstart
                  :local-file "resources/hubot.upstart")
  (service "hubot"
           :action :restart
           :service-impl :upstart))

(defplan hubot []
  (install-hubot)
  (hubot-conf)
  (start-hubot))

(defplan configure-irc []
  (package-manager :update)
  (package-manager :upgrade)
  (ngircd)
  (znc)
  (kiwi)
  (hubot))

(def irc-server 
  (group-spec
    "server.irc" 
    :node-spec (node-spec
                 ;:packager :apt
                 :image {:image-id :ubuntu-12.04})
    :phases {:bootstrap automated-admin-user
             :configure configure-irc}))

(def dev-server
  (group-spec
    "server.dev"
    :extends irc-server
    :count 1
    :roles #{:dev}))

(def prod-server
  (group-spec
    "server.prod"
    :extends irc-server
    :count 2
    :roles #{:prod}))
