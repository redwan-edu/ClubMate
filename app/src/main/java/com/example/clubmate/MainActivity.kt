package com.example.clubmate

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.clubmate.auth.LoginScreen
import com.example.clubmate.auth.PrivateChannelAuth
import com.example.clubmate.auth.RegisterScreen
import com.example.clubmate.db.Routes
import com.example.clubmate.db.Status
import com.example.clubmate.navbarScreens.AccountScreen
import com.example.clubmate.navbarScreens.DevelopersScreen
import com.example.clubmate.navbarScreens.PersonalizeScreen
import com.example.clubmate.navbarScreens.ReportBugScreen
import com.example.clubmate.navbarScreens.SecurityScreen
import com.example.clubmate.navbarScreens.SettingsScreen
import com.example.clubmate.privateChannel.CreatePrivateChannelScreen
import com.example.clubmate.privateChannel.PrivateChannel
import com.example.clubmate.screens.ChatScreen
import com.example.clubmate.screens.CreateGroupScreen
import com.example.clubmate.screens.GroupDetailsScreen
import com.example.clubmate.screens.GroupScreen
import com.example.clubmate.screens.MainScreen
import com.example.clubmate.screens.UserDetailsScreen
import com.example.clubmate.screens.grp.AddParticipants
import com.example.clubmate.screens.grp.RemoveParticipant
import com.example.clubmate.screens.grp.RequestScreen
import com.example.clubmate.screens.grp.ViewAllParticipants
import com.example.clubmate.screens.grp.ViewGroupUserDetailScreen
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.util.group.BlockUserScreen
import com.example.clubmate.util.group.ChangeRoles
import com.example.clubmate.util.group.Console
import com.example.clubmate.util.group.TimelineScreen
import com.example.clubmate.viewmodel.AuthViewModel
import com.example.clubmate.viewmodel.ChatViewModel
import com.example.clubmate.viewmodel.GroupViewmodel
import com.example.clubmate.viewmodel.MainViewmodel
import com.example.clubmate.viewmodel.PrivateChannelViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val authViewModel: AuthViewModel = viewModel()
            val chatViewModel: ChatViewModel = viewModel()
            val mainViewModel: MainViewmodel = viewModel()
            val grpViewmodel: GroupViewmodel = viewModel()

            ClubMateTheme(darkTheme = false) {
                App(
                    authViewModel = authViewModel,
                    chatViewModel = chatViewModel,
                    mainViewModel = mainViewModel,
                    groupViewmodel = grpViewmodel
                )
            }
        }
    }
}


@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun App(
    authViewModel: AuthViewModel,
    chatViewModel: ChatViewModel,
    mainViewModel: MainViewmodel,
    groupViewmodel: GroupViewmodel
) {
    val navController: NavHostController = rememberNavController()
    val authState = authViewModel.authState.observeAsState()

    val grpId = "93beefc4b3bf4d42a1d1"
    val uid = "zABknaet0eYXrv5LV7LNdPeHbr43"

    NavHost(
        navController = navController,
        startDestination = Routes.Splash
    ) {
        composable<Routes.Splash> {
            LaunchedEffect(
                authState.value
            ) {
                if (authState.value == Status.Authenticated) {
                    delay(1000)
                    navController.navigate(Routes.Main) {
                        popUpTo(Routes.Splash) {
                            inclusive = true
                        }
                    }
                } else {
                    delay(1000)
                    navController.navigate(Routes.Login) {
                        popUpTo(Routes.Splash) {
                            inclusive = true
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        composable<Routes.Login> {
            LoginScreen(authViewmodel = authViewModel, navController = navController)
        }
        composable<Routes.Register> {
            RegisterScreen(authViewmodel = authViewModel, navController = navController)
        }

        composable<Routes.Main> {
            LaunchedEffect(
                authState.value
            ) {
                if (authState.value == Status.NotAuthenticated) {
                    navController.navigate(Routes.Login) {
                        popUpTo(Routes.Main) {
                            inclusive = true
                        }
                    }
                }
            }
            MainScreen(
                chatViewmodel = chatViewModel,
                navController = navController,
                authViewModel = authViewModel,
                mainViewModel = mainViewModel,
                grpViewmodel = groupViewmodel
            )
        }

        // users routes

        composable<Routes.UserModel> {
            val args = it.toRoute<Routes.UserModel>()
            ChatScreen(
                userModel = args,
                chatViewmodel = chatViewModel,
                navController = navController,
                authViewModel = authViewModel,
                mainViewmodel = mainViewModel
            )
        }


        composable<Routes.UserDetails> {
            val args = it.toRoute<Routes.UserDetails>()
            UserDetailsScreen(
                userDetails = args,
                chatViewmodel = chatViewModel,
                navController = navController,
            )
        }

        // group routes

        composable<Routes.AddUserToGroup> {
            val args = it.toRoute<Routes.AddUserToGroup>()
            AddParticipants(grpInfo = args, grpViewmodel = groupViewmodel)
        }
        composable<Routes.Request> {
            val args = it.toRoute<Routes.Request>()
            RequestScreen(args, groupViewmodel, navController)
        }
        composable<Routes.Block> {
            val args = it.toRoute<Routes.Block>()
            BlockUserScreen(args = args, grpViewmodel = groupViewmodel)
        }
        composable<Routes.ChangeRoles> {
            val args = it.toRoute<Routes.ChangeRoles>()
            ChangeRoles(args = args, grpViewmodel = groupViewmodel, navController)
        }

        composable<Routes.RemoveUserFromGroup> {
            val args = it.toRoute<Routes.RemoveUserFromGroup>()
            RemoveParticipant(grpInfo = args, grpViewmodel = groupViewmodel)
        }

        composable<Routes.ViewAllUser> {
            val args = it.toRoute<Routes.ViewAllUser>()
            ViewAllParticipants(
                grpInfo = args,
                grpViewmodel = groupViewmodel,
                navController = navController,
                authViewModel = authViewModel
            )
        }
        composable<Routes.Console> {
            val args = it.toRoute<Routes.Console>()
            Console(args = args, grpViewmodel = groupViewmodel, navController = navController)
        }
        composable<Routes.Timeline> {
            val args = it.toRoute<Routes.Timeline>()
            TimelineScreen(
                args = args, grpViewmodel = groupViewmodel, navHostController = navController
            )
        }

        composable<Routes.CreateGroup> {
            val args = it.toRoute<Routes.CreateGroup>()
            CreateGroupScreen(
                user = args, grpViewModel = groupViewmodel, navController = navController
            )
        }
        composable<Routes.GroupModel> {
            val args = it.toRoute<Routes.GroupModel>()
            GroupScreen(
                userId = args.user,
                grpId = args.grpId,
                navController = navController,
                grpViewmodel = groupViewmodel
            )
        }

        composable<Routes.GrpDetails> {
            val args = it.toRoute<Routes.GrpDetails>()
            val uid = authViewModel.currentUser.value?.uid
            GroupDetailsScreen(
                uid = uid!!,
                grpDetails = args,
                grpViewModel = groupViewmodel,
                navController = navController,
            )
        }
        composable<Routes.GroupUserDetails> {
            val args = it.toRoute<Routes.GroupUserDetails>()
            ViewGroupUserDetailScreen(
                info = args, grpViewmodel = groupViewmodel, navController = navController
            )
        }

        // private

        composable<Routes.PrivateAuth> {
            val privateChannelViewModel: PrivateChannelViewModel = viewModel()
            PrivateChannelAuth(navController, privateChannelViewModel)
        }
        composable<Routes.PrivateChat> {
            val privateChannelViewModel: PrivateChannelViewModel = viewModel()
            val args = it.toRoute<Routes.PrivateChat>()
            PrivateChannel(
                uid = args.uid,
                channelId = args.channelId,
                password = args.password,
                viewModel = privateChannelViewModel,
                navController = navController
            )
        }

        composable<Routes.CreateChannel> {
            val viewmodel: PrivateChannelViewModel = viewModel()
            CreatePrivateChannelScreen(viewmodel = viewmodel, navController = navController)
        }

        // nav screens

        composable<Routes.Accounts> {
            AccountScreen(authViewmodel = authViewModel, navController = navController)
        }
        composable<Routes.Personalize> {
            PersonalizeScreen(authViewmodel = authViewModel, navController = navController)
        }
        composable<Routes.ReportBug> {
            ReportBugScreen(authViewmodel = authViewModel, navController = navController)
        }
        composable<Routes.Setting> {
            SettingsScreen(authViewmodel = authViewModel, navController = navController)
        }
        composable<Routes.Developers> {
            DevelopersScreen(navController = navController)
        }
        composable<Routes.Security> {
            SecurityScreen(authViewmodel = authViewModel, navController = navController)
        }
    }
}



