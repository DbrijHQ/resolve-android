# Dbrij Resolve for Android

Dbrij Resolve for **Android** in Kotlin: your support desk as a screen in your app, with signed sign in, screenshots on tickets and the unread badge. Android 6 (API 23) and later.

Add the module to your build: copy this folder into your project and `include(":resolve")` in settings.gradle.kts, then `implementation(project(":resolve"))`. It is not on Maven Central yet.

## The support screen

```kotlin
import com.dbrij.resolve.ResolveIdentity
import com.dbrij.resolve.ResolveSupport

ResolveSupport.open(
    context,
    desk = "rk_...",
    identity = ResolveIdentity(email = user.email, externalId = user.id, userHash = user.resolveHash),
)
```

The screen closes from its own close button or the back gesture. Customers attach screenshots and documents with the system picker.

## The unread badge

```kotlin
val resolve = ResolveClient(context, desk = "rk_...")
lifecycleScope.launch {
    val unread = resolve.unread(ResolveIdentity(email = user.email, externalId = user.id, userHash = user.resolveHash))
    // unread.count on your help button
}
```

Call `resolve.signOut()` from your own sign out.

## Signed sign in (recommended)

Your customers are already signed in to your product, so they should not need an emailed code from us. In Resolve, open your desk, go to **Widget and portal**, and press **Make a secret** under Signed sign in. Keep the secret on your **server**, never in the app.

For each customer, your server makes a `userHash` and your app passes it with their identity:

- the string signed is the lowercase email, or `externalId + "\n" + email` (lowercase email) when you also send an `externalId`;
- the hash is HMAC SHA256 of that string with the secret, in hex.

```js
// Node
const subject = externalId ? `${externalId}\n${email.toLowerCase()}` : email.toLowerCase();
const userHash = crypto.createHmac('sha256', process.env.RESOLVE_IDENTITY_SECRET).update(subject).digest('hex');
```

```python
# Python
subject = f"{external_id}\n{email.lower()}" if external_id else email.lower()
user_hash = hmac.new(SECRET.encode(), subject.encode(), hashlib.sha256).hexdigest()
```

```php
// PHP
$subject = $externalId ? $externalId . "\n" . strtolower($email) : strtolower($email);
$userHash = hash_hmac('sha256', $subject, getenv('RESOLVE_IDENTITY_SECRET'));
```

Without a `userHash` everything still works: customers sign in with an emailed code once per device.

## Telling customers about replies

- **Email**: customers who wrote in from an app are emailed when a reply sits unopened for two minutes, with the reply and a link back to their ticket. A burst of replies becomes one email; a reply they read in time sends nothing. A desk can turn this off under Setup.
- **Push**: in Resolve, under **Push to your app**, pick one of your Dbrij developer apps with a webhook URL. Every reply sends it a signed `support.ticket.replied` webhook with `contact.externalId`, `contact.email` and `preview`, and your server sends the push with the keys it already has for your app. Verify the `X-Dbrij-Signature-V2` header as described in the developer docs.
