package com.example.vibe_code

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.vibe_code.ui.ExpenseApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: ExpenseViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.refresh() } }
        setContent { ExpenseApp(model, intent.getBooleanExtra("openBills", false)) }
    }
}
