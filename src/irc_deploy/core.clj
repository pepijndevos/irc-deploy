(ns irc-deploy.core
  (:use [pallet.compute.vmfest :only [add-image]]
        [pallet.compute :only [instantiate-provider]]
        [pallet.api :only [group-spec, node-spec]]
        [pallet.crate :only [defplan get-settings assoc-settings]]
        [pallet.crate.automated-admin-user :only [automated-admin-user]]
        [pallet.actions :only [package package-manager remote-file directory]]
        [pallet.config-file.format :only [name-values]]))

(def vmfest (instantiate-provider "vmfest"))
;(add-image vmfest "https://s3.amazonaws.com/vmfest-images/ubuntu-12.04.vdi.gz")

(defn md5 [s]
  (org.apache.commons.codec.digest.DigestUtils/md5Hex s))

(defplan sbnc-conf []
  (let [{:keys [username password]} (get-settings :irc-admin)]
    (remote-file
      "/etc/sbnc/sbnc.conf"
      :content (name-values
                 {:system.users username
                  :system.port 6667
                  :system.md5 1}
                 :separator "="))
    
    (directory "/var/lib/sbnc/users/")
    (remote-file
      (format "/var/lib/sbnc/users/%s.conf" username) ; exploit here
      :content (name-values
                 {:user.password (md5 password)
                  :user.admin 1
                  :user.nick username
                  :user.server "localhost"
                  :user.port 6667}
                 :separator "="))))

(defplan ngircd []
  (package "ngircd"))

(defplan start-sbnc []
  (remote-file "/etc/default/sbnc" :content "AUTOSTART_SBNC=1")
  (comment initd start))

(defplan sbnc []
  (package "sbnc")
  (sbnc-conf)
  (start-sbnc))

(defplan configure-irc []
  (package-manager :update)
  (ngircd)
  (sbnc))

(defplan settings []
  (assoc-settings :irc-admin {:username "admin", :password "admin"}))

(def ubuntu-group 
  (group-spec
    "ubuntu-vms" 
    :count 1
    :node-spec (node-spec
                 :image {:os-family :ubuntu
                         :os-64-bit? true}
    :phases {:bootstrap automated-admin-user
             :configure configure-irc
             :settings settings})))
