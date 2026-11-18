@echo off
rem Free My TV, Fire TV edition: turns Amazon's apps back on (undoes menage-fire.bat).
rem First choose Original on the stick (Free My TV > Ecran d'accueil > Original).
echo Reactivation des applis d'Amazon :
adb shell pm enable com.amazon.ftvads.deeplinking
adb shell pm enable com.amazon.kso.blackbird
adb shell pm enable com.amazon.hybridadidservice
adb shell pm enable com.amazon.shoptv.client
adb shell pm enable com.amazon.shoptv.firetv.client
adb shell pm enable com.amazon.sneakpeek
adb shell pm enable com.amazon.tv.acr
adb shell pm enable com.amazon.minitv.android.app
adb shell pm enable com.amazon.tv.easyupgrade
adb shell pm enable com.amazon.tv.earlyaccess
adb shell pm enable com.amazon.tv.releasenotes
adb shell pm enable com.amazon.tmm.tutorial
adb shell pm enable com.amazon.storm.lightning.tutorial
adb shell pm enable com.amazon.device.telemetry.emitter
adb shell pm enable com.amazon.wirelessmetrics.service
adb shell pm enable com.amazon.perfcollection
adb shell pm enable com.amazon.device.crashmanager
adb shell pm revoke io.freemytv android.permission.WRITE_SECURE_SETTINGS
echo.
echo Termine : tout est comme avant.
pause
