# ClubMate

A private chat app for university clubs. You run it on your own free Firebase account, so your club's
messages live in a place you control, and they are end-to-end encrypted on top of that.

## Why we built it

Our clubs ran on a mix of WhatsApp groups, Messenger chats and notice boards pinned in someone's
inbox. Announcements got buried under small talk, new members had no idea where to look, and none
of it belonged to the club.

ClubMate puts all of it in one app: chats, club groups with roles, a notice board for events and
meetings, and private channels for sensitive talk. We're open sourcing it so any club, society or
small team can run their own copy.

## What you can do with it

- **One-to-one chats** with text and photos. There's also an incognito mode where messages are
  deleted when you leave the chat.
- **Groups** for your club. Members get roles (admin, president, vice president, treasurer or member). Admins approve join
  requests, add or remove people, and see a small admin console.
- **A notice board** in every group, with Events, Meetings and Notices. Links in a post become buttons.
- **Private channels** that anyone with the channel ID and password can join. Messages vanish, and
  people show up under nicknames like "Blue Otter" instead of their names.
- **End-to-end encryption** everywhere. Messages, notices and photos are locked on the phone before
  they're sent, so even the owner of the Firebase account can't read them.
- Light and dark mode, following your phone's setting.

## What it uses

| Part | What it's for | Cost |
|---|---|---|
| **Android** | The app itself. It runs on Android 7.0 or newer. | Free |
| **Firebase** (by Google) | Sign-in with email and password, and the database that carries messages between phones. | The free plan is enough for a club |
| **Cloudinary** | Storing photos. Photos are encrypted before upload, so Cloudinary only stores scrambled files. | The free plan is enough for a club |

You create your own Firebase and Cloudinary accounts and connect them to the app. Nothing goes
through our servers.

## What you need

- A computer (Windows, Mac or Linux) with [Android Studio](https://developer.android.com/studio)
  installed. Use a recent version.
- An Android phone running Android 7.0 or newer, and a USB cable.
- A Google account, for Firebase.
- An email address, for a free Cloudinary account.

Plan for about 30 minutes the first time.

## Setup

### Step 1: Get the code

Download this project, either with the green **Code → Download ZIP** button on GitHub (then unzip
it), or with git:

```
git clone https://github.com/redwan-edu/ClubMate.git
```

Open Android Studio, choose **Open**, and pick the `ClubMate` folder. Let it finish loading. The first
time can take a few minutes while it downloads what it needs.

### Step 2: Connect Firebase

1. Go to the [Firebase console](https://console.firebase.google.com) and click **Create a project**.
   Give it any name, like "Robotics Club Chat". You can turn off Google Analytics; the app doesn't
   use it.

2. **Turn on email sign-in.** In the left menu open **Build → Authentication**, click **Get started**,
   choose **Email/Password**, switch it on and save.

3. **Create the database.** Open **Build → Realtime Database** and click **Create database**. Pick
   the location closest to your members and start in **locked mode**.

   Then open the **Rules** tab, replace everything there with the text below, and click **Publish**.
   It means only people who are signed in to your app can use the database.

   ```json
   {
     "rules": {
       ".read": "auth != null",
       ".write": "auth != null"
     }
   }
   ```

4. **Add the Android app.** Go back to the project's home page (**Project Overview**) and click the
   Android icon to add an app. For the package name, type exactly:

   ```
   com.example.clubmate
   ```

   Leave the other fields empty and click **Register app**.

5. **Download `google-services.json`** when Firebase offers it, and put it inside the `app` folder of
   the project, next to `build.gradle.kts`:

   ```
   ClubMate/
   └── app/
       ├── google-services.json   ← here
       └── build.gradle.kts
   ```

   Skip the remaining steps Firebase shows you; the project is already set up for them.

Do step 3 before step 5. The file includes your database address, so if you created the database
later, download the file again from **Project settings → Your apps**.

### Step 3: Connect Cloudinary (for photos)

1. Sign up for a free account at [cloudinary.com](https://cloudinary.com).
2. In the Cloudinary console, open **Settings → API Keys**. Note three things: your **Cloud name**,
   your **API Key** and your **API Secret**.
3. In the main `ClubMate` folder, open the file called `cloudinary.properties` and paste your
   values after the `=` signs:

   ```
   cloudName=your-cloud-name
   apiKey=your-api-key
   apiSecret=your-api-secret
   ```

Keep your filled-in `cloudinary.properties` to yourself. If you publish your own copy of the code,
empty the three values first. `google-services.json` is never uploaded to GitHub, so you don't need
to worry about that one.

### Step 4: Install it on your phone

1. **Turn on developer mode on your phone.** Open **Settings → About phone** and tap **Build number**
   seven times. Then go to **Settings → Developer options** (sometimes under **System**) and turn on
   **USB debugging**.
2. Plug the phone into your computer and allow the "USB debugging" prompt on the phone.
3. In Android Studio, pick your phone in the device list at the top, and press the green **Run**
   button (▶).

The app installs and opens on your phone.

**Installing on other members' phones.** In Android Studio choose **Build → Build App Bundle(s) /
APK(s) → Build APK(s)**. When it finishes, click **locate** to find the `.apk` file. Send it to your
members; they open it on their phone and allow installing from that source when Android asks.
Everyone who installs your copy shares your Firebase project, so they can all find and message each
other.

## Using the app

1. **Create an account** with your email, a username and a password. The password needs at least
   8 characters, with an uppercase letter, a lowercase letter, a number and a symbol (`@$!%*?&`).
2. **Verify your email.** Firebase sends you a link. It often lands in spam. You can't log in until
   you've clicked it.
3. **Log in.** Start a chat by searching for someone's email or username.
4. **Make a group** for your club from the **Groups** tab. Share the group ID with members; they search
   for it and send a join request, which an admin approves. Admins can also add people by email.
5. **Post on the notice board** from the group's info page, and pick whether a post is for everyone
   or only for one role, like treasurers.
6. **Private channels** are in the **Channels** tab. Choose a password of at least 8 characters and
   share it only with the people who should be in.

To check that no one is listening in on a chat, open the other person's profile and compare the
**safety number** with theirs, in person. If the numbers match, the chat is secure.

## Good to know

- **Messages stay on the phone they were read on.** Because of the encryption, a message can't be
  downloaded again later. If someone reinstalls the app or switches phones, they won't see their
  old messages, and they can't read group messages sent before they joined.
- **Your Cloudinary keys are built into the app.** Share your APK only with people you trust, like
  your club members, not on public download sites.
- **Everyone on your copy shares one Firebase project.** Firebase can see who talks to whom and when,
  but not what was said.

## If something goes wrong

| Problem | What to try |
|---|---|
| Android Studio says `google-services.json` is missing | Check that the file is in the `app` folder and is named exactly `google-services.json`. |
| "No matching client found for package name" | The package name in Firebase must be exactly `com.example.clubmate`. Add the app again with that name and download a new file. |
| "Please verify your email before logging in" | Click the link in the verification email. Look in spam. |
| Chats don't load, or "Permission denied" | Check that the Realtime Database exists and that you published the rules in step 2. |
| Photos won't upload | Check the three values in `cloudinary.properties` for typos, then run the app again. |
| The phone doesn't show up in Android Studio | Try another cable, and accept the USB debugging prompt on the phone. |

## Make it your own

- **App name:** change `ClubMate` in `app/src/main/res/values/strings.xml`.
- **App icon:** in Android Studio, right-click the `app` folder and choose **New → Image Asset**.
- **Team page and "Report a problem":** these point to the original team. You'll find them in
  `app/src/main/java/com/example/clubmate/ui/AppNavHost.kt`.

## The team

- **Redwan Hussain**: project lead, app and encryption
- **Mizanur Rahman**: database design
- **Tonmoy Chanda**: UI design
- **Abu Adnan Shad**: testing

Found a bug or have an idea? Open an issue on GitHub.
