package com.example.casha.ui

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import android.content.SharedPreferences
import com.example.casha.R

internal fun petotSoundResource(mood:PetotMood):Int?=when(mood){PetotMood.CHEER,PetotMood.PROUD->R.raw.petot_cheer;PetotMood.CARE->R.raw.petot_care;else->null}
internal fun companionSoundEnabled(context:Context)=CompanionPreference.prefs(context).getBoolean("sound",true)
@Composable internal fun rememberCompanionSound():State<Boolean> {
    val context=LocalContext.current.applicationContext
    val value=remember(context){mutableStateOf(companionSoundEnabled(context))}
    DisposableEffect(context) {
        val prefs=CompanionPreference.prefs(context)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,_->value.value=companionSoundEnabled(context)}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return value
}
@Composable internal fun PetotSound(reaction:PetotReaction) {
    val context=LocalContext.current
    val enabled by rememberCompanionSound()
    DisposableEffect(reaction,enabled) {
        var player:MediaPlayer?=null
        var released=false
        fun release(){if(!released){released=true;runCatching {player?.release()}}}
        val resource=petotSoundResource(reaction.mood)
        if(enabled&&resource!=null)runCatching {
            val audio=context.getSystemService(AudioManager::class.java)
            val notifications=context.getSystemService(NotificationManager::class.java)
            val filter=notifications.currentInterruptionFilter
            if(audio.ringerMode==AudioManager.RINGER_MODE_NORMAL&&audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)>0&&
                (filter==NotificationManager.INTERRUPTION_FILTER_ALL||filter==NotificationManager.INTERRUPTION_FILTER_UNKNOWN)) {
                val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                player=MediaPlayer.create(context,resource,attributes,AudioManager.AUDIO_SESSION_ID_GENERATE)
                player?.setOnCompletionListener {release()}
                player?.setOnErrorListener {_,_,_->release();true}
                player?.start()
            }
        }.onFailure {release()}
        onDispose {release()}
    }
}
