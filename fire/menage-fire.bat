@echo off
rem Free My TV, Fire TV edition: run once from the platform-tools folder, with the stick connected (adb connect IP:5555).
rem Turns off Amazon's ad, shopping, tracking and tutorial apps (never uninstalls them) and lets Free My TV
rem turn its Home button service on and off by itself. restaurer-fire.bat undoes it.
echo Free My TV peut maintenant gerer le bouton Accueil :
adb shell pm grant io.freemytv android.permission.WRITE_SECURE_SETTINGS
rem Lets the family edition install its own updates (Android still asks on screen each time).
adb shell appops set io.freemytv REQUEST_INSTALL_PACKAGES allow
echo.
echo Desactivation des applis de pub et de suivi d'Amazon :
rem ads that open from the home screen
adb shell pm disable-user --user 0 com.amazon.ftvads.deeplinking
rem Special Offers (ads)
adb shell pm disable-user --user 0 com.amazon.kso.blackbird
rem advertising ID
adb shell pm disable-user --user 0 com.amazon.hybridadidservice
rem shopping on the TV
adb shell pm disable-user --user 0 com.amazon.shoptv.client
adb shell pm disable-user --user 0 com.amazon.shoptv.firetv.client
rem trailers that play by themselves
adb shell pm disable-user --user 0 com.amazon.sneakpeek
rem ACR: recognizes what's on screen
adb shell pm disable-user --user 0 com.amazon.tv.acr
rem miniTV (India)
adb shell pm disable-user --user 0 com.amazon.minitv.android.app
rem "upgrade your device" offers
adb shell pm disable-user --user 0 com.amazon.tv.easyupgrade
rem preview programme, release notes
adb shell pm disable-user --user 0 com.amazon.tv.earlyaccess
adb shell pm disable-user --user 0 com.amazon.tv.releasenotes
rem tutorials
adb shell pm disable-user --user 0 com.amazon.tmm.tutorial
adb shell pm disable-user --user 0 com.amazon.storm.lightning.tutorial
rem telemetry
adb shell pm disable-user --user 0 com.amazon.device.telemetry.emitter
adb shell pm disable-user --user 0 com.amazon.wirelessmetrics.service
rem performance and crash reports
adb shell pm disable-user --user 0 com.amazon.perfcollection
adb shell pm disable-user --user 0 com.amazon.device.crashmanager
echo.
echo Termine. Sur le Stick : Free My TV ^> Ecran d'accueil ^> Free My TV.
pause
