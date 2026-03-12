package com.jayathu.automata

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.jayathu.automata.ui.MainViewModel
import com.jayathu.automata.ui.navigation.AutomataNavGraph
import com.jayathu.automata.ui.theme.AutomataTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutomataTheme {
                val navController = rememberNavController()
                val viewModel: MainViewModel = viewModel()
                AutomataNavGraph(
                    navController = navController,
                    viewModel = viewModel
                )
            }
        }
    }
}
