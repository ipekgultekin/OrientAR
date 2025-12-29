package com.example.orientar // package name of file

import android.content.Intent //to open another screen  like chatbot -> treasure hunt
import android.os.Bundle //used in onCreate() to keep small data when the app restarts
import android.widget.Toast//to show shot msg on the screen
import androidx.activity.ComponentActivity //main activity class for Compose
import androidx.activity.compose.setContent //compose
import androidx.compose.foundation.Image //for image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.* // row, column,box, padding etc
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.* //to reach design components such as button, scaffold vs
import androidx.compose.runtime.Composable //to create ui function annotation (composable)
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier //padding, color settings etc
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale // scale
import androidx.compose.ui.platform.LocalContext // to take android context (like "this" we use localcontext.current)
import androidx.compose.ui.res.painterResource // to pull images (metu_logo, campus_banner) from drawable
import androidx.compose.ui.text.font.FontWeight //font, bold...
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp // units for space, width, and height
import androidx.compose.ui.unit.sp //font size unit
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll //to scroll vertically
import com.google.firebase.FirebaseApp //firebase initialization

//The Home screen was built with Jetpack Compose.
//setContent was used to show the Compose UI inside ComponentActivity

class MainActivity : ComponentActivity() { //main screen class to easily use component with activity
    override fun onCreate(savedInstanceState: Bundle?) {//check saved instance
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this) //firebase started
        setContent { //compose ui
            MaterialTheme {
                OrientationScreen() //renders the main compose UI
            }
        }
    }
}
//a clean structure was provided by Scaffold with a top bar and a bottom bar.
//the banner image and the red text overlay were created with Box
//row and column were used for the menu layout, and equal card widths were achieved with weight(1f).
//LocalContext.current was used to access the Android context inside Compose,
// so Toast messages could be shown and other screens could be opened with Intent.

//the same top bar and bottom bar are used, so the user is not lost.
//MenuCard is used as a reusable component, so new menu items can be added easily.
//vertical scrolling is enabled, so the screen can be used on small phones too.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrientationScreen() { //home screen
    val metuRed = Color(0xFF8B0000)
    val context = LocalContext.current //to take android content with local content

    Scaffold( //main skeleton, content layout in here
        topBar = {
            TopAppBar(
                title = { //metu logo + title
                    Row(
                        verticalAlignment = Alignment.CenterVertically, //row allignment
                        modifier = Modifier.padding(start = 8.dp) // padding from left
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.metu_logo), //to reach drawable metu_logo
                            contentDescription = "METU Logo",
                            modifier = Modifier.size(36.dp) //size
                        )
                        Spacer(modifier = Modifier.width(12.dp)) //space between logo and title
                        Text(
                            text = "METU NCC ORIENTATION", //title text
                            fontSize = 18.sp,  //title features
                            fontWeight = FontWeight.Bold,
                            color = metuRed
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White //top bar container color
                )
            )
        },
        bottomBar = {
            MainBottomBar()//home +orientation unit+ profile
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(bottom = 80.dp)
        ) {

            Box( //top banner campus image
                modifier = Modifier
                    .fillMaxWidth() //fill full width of screen
                    .height(240.dp) //image height
            ) {
                Image(
                    painter = painterResource(id = R.drawable.campus_banner),
                    contentDescription = "METU NCC Campus",
                    modifier = Modifier.fillMaxSize(),  //image should completely fill the box area.
                    contentScale = ContentScale.Crop //if  doesn't fit, it will crop
                )

                Box( //+ welcome to your new life and lets discover text + red transparent overlay on the banner
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .align(Alignment.BottomCenter) //puts that overlay at the bottom center of the banner image
                        .background(Color(0xAA8B0000)) //AA transparant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp), //inner spacing to prevent text from sticking to the edges
                        horizontalAlignment = Alignment.CenterHorizontally,//text is centered both horizontally and vertically
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text( //big title
                            text = "Welcome to your new life!",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(// small title
                            text = "Let's discover the campus",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp)) //space between banner and cards

            // Menü grid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row( //first row with campus tour + faq + treasure hunt
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp) //space between cards
                ) {
                    MenuCard(
                        title = "Campus Tour",
                        icon = "🔍",
                        modifier = Modifier.weight(1f),////same weight for cards
                        onClick = {
                            Toast.makeText(context, "Coming Soon...", Toast.LENGTH_SHORT).show() //coming soon toast for not implemented parts
                        }
                    )
                    MenuCard(
                        title = "FAQ",
                        icon = "💬",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val intent = Intent(context, ChatbotActivity::class.java) //directed to chatbotactivity (intend)
                            context.startActivity(intent)
                        }
                    )

                    MenuCard(
                        title = "Treasure Hunt",
                        icon = "🎁",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val intent = Intent(context, ScoreboardActivity::class.java)//directed to scoreboard activity
                            context.startActivity(intent)
                        }
                    )
                }

                Row( //second row (societies+ announcements)
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MenuCard(
                        title = "Student Societies",
                        icon = "👥",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            Toast.makeText(context, "Coming Soon...", Toast.LENGTH_SHORT).show()
                        }
                    )
                    MenuCard(
                        title = "Announcements",
                        icon = "🔔",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            Toast.makeText(context, "Coming Soon...", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(modifier = Modifier.weight(1f)) //no card, space left and aligned according to the row
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuCard(
    title: String, //card title
    icon: String, //card icon
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {} //action to be performed when the card is clicked
) {
    Card( //card features with actions
        modifier = modifier
            .height(130.dp) //card heigt
            .border(2.dp, Color(0xFF8B0000), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        onClick = onClick //when the card is clicked, the external function is activated.
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp), //for emojis and inside text
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = icon, fontSize = 40.sp)
            Spacer(modifier = Modifier.height(12.dp))// space between emoji and text
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = Color.Black,
                maxLines = 2, //max 2 line for card text
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun MainBottomBar() { //bottom bar with 3 icon
    val context = LocalContext.current //reach context

    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp // slight shadow, embossing effect on the bottom bar
    ) {
        NavigationBarItem(
            icon = { Text("🏠", fontSize = 24.sp) },
            label = {
                Text(
                    "Home",
                    fontSize = 11.sp,
                    maxLines = 1
                )
            },
            selected = true, //initially selected one
            onClick = { }, //there is no action when pressed home again (already at home initially)
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF8B0000),//if selected ,then icon and text color:
                selectedTextColor = Color(0xFF8B0000),
                indicatorColor = Color.Transparent
            )
        )
        NavigationBarItem(
            icon = { Text("📋", fontSize = 24.sp) },
            label = {
                Text(
                    "My Orientation\nUnit",
                    fontSize = 10.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    lineHeight = 11.sp
                )
            },
            selected = false,
            onClick = {
                Toast.makeText(context, "Coming Soon...", Toast.LENGTH_SHORT).show() //not implemented yet
            },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray, //if not selected then:
                unselectedTextColor = Color.Gray
            )
        )
        NavigationBarItem(
            icon = { Text("👤", fontSize = 24.sp) },
            label = {
                Text(
                    "Profile",
                    fontSize = 11.sp,
                    maxLines = 1
                )
            },
            selected = false,
            onClick = {
                Toast.makeText(context, "Coming Soon...", Toast.LENGTH_SHORT).show()
            },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray
            )
        )
    }
}
//this UI design is made easy to maintain because
// reusable composable functions are used
// (for example, MenuCard() and MainBottomBar()).
//In this way, changes are made in one place and
// the same update is applied to all screens.