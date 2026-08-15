package com.campuslink.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.campuslink.mobile.ui.CampusLinkApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CampusLinkApp((application as CampusLinkApplication).container) }
    }
}
