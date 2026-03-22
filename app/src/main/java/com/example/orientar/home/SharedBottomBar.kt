package com.example.orientar.home

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MetuRed = Color(0xFF8B0000)

/**
 * Shared bottom navigation bar used across all activities.
 * Usage:
 *   SharedBottomBar(userRole = userRole)
 */
@Composable
fun SharedBottomBar(userRole: String = "student") {
    val context = LocalContext.current
    val isGuest = userRole == "guest"

    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        NavigationBarItem(
            icon     = { Icon(Icons.Outlined.Home, null) },
            label    = { Text("Home", fontSize = 11.sp) },
            selected = false,
            onClick  = {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("USER_ROLE", userRole)
                    }
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor   = MetuRed,
                selectedTextColor   = MetuRed,
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor      = MetuRed.copy(alpha = 0.1f)
            )
        )
        if (!isGuest) {
            NavigationBarItem(
                icon     = { Icon(Icons.Outlined.Groups, null) },
                label    = { Text("My Unit", fontSize = 11.sp) },
                selected = false,
                onClick  = {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("USER_ROLE", userRole)
                            putExtra("OPEN_TAB", 1)
                        }
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor   = MetuRed,
                    selectedTextColor   = MetuRed,
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor      = MetuRed.copy(alpha = 0.1f)
                )
            )
        }
        NavigationBarItem(
            icon     = { Icon(Icons.Outlined.Person, null) },
            label    = { Text("Profile", fontSize = 11.sp) },
            selected = false,
            onClick  = {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("USER_ROLE", userRole)
                        putExtra("OPEN_TAB", if (isGuest) 1 else 2)
                    }
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor   = MetuRed,
                selectedTextColor   = MetuRed,
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor      = MetuRed.copy(alpha = 0.1f)
            )
        )
    }
}