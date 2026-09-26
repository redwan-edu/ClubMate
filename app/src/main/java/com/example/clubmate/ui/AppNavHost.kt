package com.example.clubmate.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.clubmate.R
import com.example.clubmate.db.Routes
import com.example.clubmate.db.Status
import com.example.clubmate.ui.auth.LoginRoute
import com.example.clubmate.ui.auth.RegisterRoute
import com.example.clubmate.ui.auth.SplashScreen
import com.example.clubmate.ui.channel.ChannelChatRoute
import com.example.clubmate.ui.channel.ChannelsTabRoute
import com.example.clubmate.ui.channel.CreateChannelRoute
import com.example.clubmate.ui.chat.ChatRoute
import com.example.clubmate.ui.chat.ContactProfileRoute
import com.example.clubmate.ui.group.AddMemberRoute
import com.example.clubmate.ui.group.ConsoleRoute
import com.example.clubmate.ui.group.CreateGroupRoute
import com.example.clubmate.ui.group.GroupChatRoute
import com.example.clubmate.ui.group.GroupInfoRoute
import com.example.clubmate.ui.group.MemberDetailRoute
import com.example.clubmate.ui.group.MembersRoute
import com.example.clubmate.ui.group.NewPostRoute
import com.example.clubmate.ui.group.NoticeBoardRoute
import com.example.clubmate.ui.home.HomeRoute
import com.example.clubmate.ui.settings.PrivacyRoute
import com.example.clubmate.ui.settings.appVersionName
import com.example.clubmate.ui.settings.ProfileRoute
import com.example.clubmate.ui.settings.SettingsTabRoute
import com.example.clubmate.ui.settings.TeamMember
import com.example.clubmate.ui.settings.TeamScreen
import com.example.clubmate.viewmodel.AuthViewModel
import com.example.clubmate.viewmodel.ChatViewModel
import com.example.clubmate.viewmodel.GroupViewmodel
import com.example.clubmate.viewmodel.PrivateChannelViewModel
import kotlinx.coroutines.delay

private const val TRANSITION_MS = 260

/** Every screen of the app and how they connect. */
@Composable
fun ClubMateApp(authViewModel: AuthViewModel) {
    val nav = rememberNavController()
    val authState by authViewModel.authState.observeAsState()
    val currentUser by authViewModel.currentUser.collectAsState()
    val myUid = currentUser?.uid.orEmpty()

    // Keyed by account, so signing in as someone else starts with fresh lists and listeners.
    val chatViewModel: ChatViewModel = viewModel(key = "chat-$myUid")
    val groupViewModel: GroupViewmodel = viewModel(key = "group-$myUid")

    NavHost(
        navController = nav,
        startDestination = Routes.Splash,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        enterTransition = { slideInHorizontally(tween(TRANSITION_MS)) { it / 5 } + fadeIn(tween(TRANSITION_MS)) },
        exitTransition = { slideOutHorizontally(tween(TRANSITION_MS)) { -it / 10 } + fadeOut(tween(TRANSITION_MS)) },
        popEnterTransition = { slideInHorizontally(tween(TRANSITION_MS)) { -it / 10 } + fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = { slideOutHorizontally(tween(TRANSITION_MS)) { it / 5 } + fadeOut(tween(TRANSITION_MS)) }
    ) {
        // ------------------------------------------------------------ auth

        composable<Routes.Splash> {
            SplashScreen()
            LaunchedEffect(authState) {
                if (authState == null || authState == Status.Loading) return@LaunchedEffect
                delay(400)
                val next: Any = if (authState == Status.Authenticated) Routes.Main else Routes.Login
                nav.navigate(next) { popUpTo<Routes.Splash> { inclusive = true } }
            }
        }
        composable<Routes.Login> {
            LoginRoute(
                authViewModel = authViewModel,
                onLoggedIn = { nav.navigate(Routes.Main) { popUpTo<Routes.Login> { inclusive = true } } },
                onCreateAccount = { nav.navigate(Routes.Register) { launchSingleTop = true } }
            )
        }
        composable<Routes.Register> {
            RegisterRoute(
                authViewModel = authViewModel,
                onRegistered = { nav.popBackStack() },
                onLogin = { nav.popBackStack() }
            )
        }

        // ------------------------------------------------------------ home

        composable<Routes.Main> {
            LaunchedEffect(authState) {
                if (authState == Status.NotAuthenticated) {
                    nav.navigate(Routes.Login) { popUpTo<Routes.Main> { inclusive = true } }
                }
            }
            if (myUid.isEmpty()) return@composable
            val channelJoinViewModel: PrivateChannelViewModel = viewModel()
            HomeRoute(
                myUid = myUid,
                authViewModel = authViewModel,
                chatViewModel = chatViewModel,
                groupViewModel = groupViewModel,
                onOpenChat = { nav.navigate(it) },
                onOpenGroup = { nav.navigate(Routes.GroupModel(user = myUid, grpId = it)) },
                onCreateGroup = { nav.navigate(Routes.CreateGroup) },
                channelsContent = {
                    ChannelsTabRoute(
                        myUid = myUid,
                        viewModel = channelJoinViewModel,
                        onOpen = { nav.navigate(it) },
                        onCreate = { nav.navigate(Routes.CreateChannel) }
                    )
                },
                settingsContent = {
                    SettingsTabRoute(
                        authViewModel = authViewModel,
                        onProfile = { nav.navigate(Routes.Accounts) },
                        onPrivacy = { nav.navigate(Routes.Security) },
                        onTeam = { nav.navigate(Routes.Developers) }
                    )
                }
            )
        }

        // ------------------------------------------------------------ 1:1 chats

        composable<Routes.UserModel> { entry ->
            val args = entry.toRoute<Routes.UserModel>()
            ChatRoute(
                args = args,
                myUid = myUid,
                chatViewModel = chatViewModel,
                onBack = { nav.popBackStack() },
                onOpenProfile = {
                    nav.navigate(
                        Routes.UserDetails(email = args.email, uid = args.uid, username = args.username, chatID = args.chatID, phone = args.phone)
                    )
                }
            )
        }
        composable<Routes.UserDetails> { entry ->
            ContactProfileRoute(
                args = entry.toRoute<Routes.UserDetails>(),
                myUid = myUid,
                chatViewModel = chatViewModel,
                onBack = { nav.popBackStack() },
                onChatDeleted = { nav.backToHome() }
            )
        }

        // ------------------------------------------------------------ groups

        composable<Routes.CreateGroup> {
            CreateGroupRoute(
                myUid = myUid,
                groupViewModel = groupViewModel,
                onBack = { nav.popBackStack() },
                onCreated = { grpId ->
                    nav.navigate(Routes.GroupModel(user = myUid, grpId = grpId)) {
                        popUpTo<Routes.CreateGroup> { inclusive = true }
                    }
                }
            )
        }
        composable<Routes.GroupModel> { entry ->
            val args = entry.toRoute<Routes.GroupModel>()
            GroupChatRoute(
                grpId = args.grpId,
                myUid = myUid,
                groupViewModel = groupViewModel,
                onBack = { nav.popBackStack() },
                onOpenInfo = { details -> nav.navigate(details.copy(grpId = args.grpId)) },
                onOpenNotices = { nav.navigate(Routes.Timeline(grpId = args.grpId, uid = myUid)) }
            )
        }
        composable<Routes.GrpDetails> { entry ->
            val args = entry.toRoute<Routes.GrpDetails>()
            GroupInfoRoute(
                args = args,
                myUid = myUid,
                groupViewModel = groupViewModel,
                onBack = { nav.popBackStack() },
                onOpenNotices = { nav.navigate(Routes.Timeline(grpId = args.grpId, uid = myUid)) },
                onOpenConsole = { details ->
                    nav.navigate(
                        Routes.Console(
                            grpId = details.grpId, grpName = details.grpName, image = details.photoUrl,
                            description = details.description, uid = myUid
                        )
                    )
                },
                onOpenMembers = { nav.navigate(Routes.ViewAllUser(args.grpId)) },
                onMemberClick = { member ->
                    nav.navigate(Routes.GroupUserDetails(grpId = args.grpId, grpName = args.grpName, userId = member.uid, currentUserId = myUid))
                },
                onLeft = { nav.backToHome() }
            )
        }
        composable<Routes.ViewAllUser> { entry ->
            val args = entry.toRoute<Routes.ViewAllUser>()
            MembersRoute(
                grpId = args.grpId,
                groupViewModel = groupViewModel,
                onBack = { nav.popBackStack() },
                onMemberClick = { member ->
                    nav.navigate(Routes.GroupUserDetails(grpId = args.grpId, userId = member.uid, currentUserId = myUid))
                }
            )
        }
        composable<Routes.GroupUserDetails> { entry ->
            MemberDetailRoute(
                args = entry.toRoute<Routes.GroupUserDetails>(),
                groupViewModel = groupViewModel,
                onBack = { nav.popBackStack() }
            )
        }
        composable<Routes.Console> { entry ->
            val args = entry.toRoute<Routes.Console>()
            ConsoleRoute(
                args = args,
                groupViewModel = groupViewModel,
                onBack = { nav.popBackStack() },
                onAddMember = { nav.navigate(Routes.AddUserToGroup(args.grpId)) },
                onManageMembers = { nav.navigate(Routes.ViewAllUser(args.grpId)) },
                onNewPost = { nav.navigate(Routes.NewPost(args.grpId)) }
            )
        }
        composable<Routes.AddUserToGroup> { entry ->
            AddMemberRoute(
                grpId = entry.toRoute<Routes.AddUserToGroup>().grpId,
                groupViewModel = groupViewModel,
                onBack = { nav.popBackStack() }
            )
        }
        composable<Routes.Timeline> { entry ->
            val args = entry.toRoute<Routes.Timeline>()
            NoticeBoardRoute(
                grpId = args.grpId,
                myUid = myUid,
                groupViewModel = groupViewModel,
                onBack = { nav.popBackStack() },
                onNewPost = { nav.navigate(Routes.NewPost(args.grpId)) }
            )
        }
        composable<Routes.NewPost> { entry ->
            NewPostRoute(
                grpId = entry.toRoute<Routes.NewPost>().grpId,
                groupViewModel = groupViewModel,
                onBack = { nav.popBackStack() }
            )
        }

        // ------------------------------------------------------------ private channels

        composable<Routes.CreateChannel> {
            CreateChannelRoute(
                myUid = myUid,
                viewModel = viewModel(),
                onBack = { nav.popBackStack() },
                onCreated = { channel ->
                    nav.navigate(channel) { popUpTo<Routes.CreateChannel> { inclusive = true } }
                }
            )
        }
        composable<Routes.PrivateChat> { entry ->
            // one view model per open channel: it holds that channel's key
            ChannelChatRoute(
                args = entry.toRoute<Routes.PrivateChat>(),
                viewModel = viewModel(),
                onBack = { nav.popBackStack() },
                onDeleted = { nav.backToHome() }
            )
        }

        // ------------------------------------------------------------ settings

        composable<Routes.Accounts> {
            ProfileRoute(authViewModel = authViewModel, onBack = { nav.popBackStack() })
        }
        composable<Routes.Security> {
            PrivacyRoute(myUid = myUid, onBack = { nav.popBackStack() })
        }
        composable<Routes.Developers> {
            TeamRoute(onBack = { nav.popBackStack() })
        }
    }
}

/** Returns to the home tabs, dropping everything above them. */
private fun NavHostController.backToHome() {
    if (!popBackStack<Routes.Main>(inclusive = false)) navigate(Routes.Main)
}

@Composable
private fun TeamRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val team = listOf(
        TeamMember("Redwan Hussain", "Developer", "redwan491560@gmail.com", painterResource(R.drawable.redwan), zoom = 3.3f),
        TeamMember("Mizanur Rahman", "Developer", "mizan21331@gmail.com", painterResource(R.drawable.mizan)),
        TeamMember("Tonmoy Chanda", "Developer", "tonmoychanda07@gmail.com", painterResource(R.drawable.tonmoy)),
        TeamMember("Abu Adnan Shad", "Developer", "adnanshad1035@gmail.com", painterResource(R.drawable.adnan)),
    )
    TeamScreen(
        members = team,
        appVersion = appVersionName(context),
        onEmail = { email ->
            try {
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
            } catch (e: Exception) {
                Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
            }
        },
        onBack = onBack
    )
}
