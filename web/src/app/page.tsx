import { redirect } from "next/navigation";

// The library is the app's front door once there is a session. Keeping "/" as an alias means old
// links and bookmarks still land somewhere real.
export default function RootPage() {
  redirect("/library");
}
