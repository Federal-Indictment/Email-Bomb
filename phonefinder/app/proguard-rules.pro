# Keep SpeechRecognizer callbacks
-keep class * implements android.speech.RecognitionListener { *; }
# Keep BootReceiver
-keep class com.phonefinder.BootReceiver { *; }
# Keep AlarmController
-keep class com.phonefinder.AlarmController { *; }
