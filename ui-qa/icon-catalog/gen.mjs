import fs from 'node:fs';
const dir = 'node_modules/@fluentui/svg-icons/icons/';
const list = `
weather_sunny|morning sunrise light day
weather_moon|night sleep evening
sleep|bed rest nap
bed|sleep rest night
alarm_on|wake wakeup morning alarm
clock|time schedule
clock_alarm|alarm wake
timer|stopwatch focus pomodoro
hourglass|time wait patience
calendar|date plan schedule
calendar_checkmark_center|plan planning day agenda
calendar_agenda|plan list schedule
target|goal aim focus
target_arrow|goal aim focus bullseye
flag|goal milestone
trophy|win achievement prize
medal|win achievement award
crown|king best win
ribbon|award badge
star|favorite rate
sparkle|magic new clean shine
rocket|launch start project
lightbulb|idea think creative
brain|mind think learn mental
brain_circuit|mind think focus deep work ai
heart|love health favorite
heart_pulse|health cardio heartbeat fitness
heart_circle|love care
stethoscope|doctor health checkup medical
pill|medicine vitamins supplement meds
briefcase_medical|first aid health
run|running jog cardio exercise fitness
person_walking|walk steps stroll
vehicle_bicycle|bike cycling ride
dumbbell|gym weights strength workout lift
sport_soccer|football sport
sport_basketball|sport ball
sport_baseball|sport ball
sport_hockey|sport
sport_cricket_bat|sport
person_running|run exercise
drop|water hydrate drink
water|water hydrate drink
drink_coffee|tea cup caffeine
drink_wine|alcohol drink
drink_beer|alcohol drink
drink_to_go|cup drink
food|eat meal diet
food_apple|fruit healthy eat diet
food_carrot|vegetable healthy eat diet
food_egg|breakfast protein eat
food_fish|seafood protein eat
food_chicken_leg|meat protein eat
food_pizza|junk eat
food_cake|dessert sweet eat
food_toast|breakfast eat
food_grains|diet eat
bowl_salad|salad healthy meal diet
bowl_chopsticks|meal dinner eat
cookies|snack sweet
showerhead|shower wash clean hygiene
weather_rain_showers_day|rain
weather_cloudy|cloud
weather_snow|snow cold
fire|streak hot burn calories
leaf_one|nature plant green
leaf_three|nature plant green
tree_deciduous|nature outdoors
earth_leaf|nature eco planet
animal_dog|pet walk dog
animal_cat|pet cat
animal_rabbit|pet
animal_paw_print|pet paw
book|read reading study
book_open|read reading study
book_open_lightbulb|learn study idea
notebook|journal diary notes write
calligraphy_pen|write writing journal pen
pen|write writing sign
edit|write pencil edit
document|file doc paper
clipboard_task|checklist tasks todo
clipboard_checkmark|checklist tasks todo
task_list_square|checklist tasks todo
checkmark_circle|done check complete habit
checkmark|done tick
checkmark_starburst|done verified quality
list|todo items list
glasses|reading eyes screen
eye|look see vision
microscope|science research
beaker|science chemistry experiment
math_formula|math maths study
calculator|math budget numbers
translate|language learn speak
globe|world language travel
book_globe|language learn world
music_note_1|music song practice
music_note_2|music song practice
headphones|music listen podcast audio
mic|record speak podcast voice
guitar|music instrument practice
video|film movie record
camera|photo picture photography
image|photo picture
paint_brush|art draw paint creative
paint_bucket|art paint
color|palette art color
puzzle_piece|puzzle game hobby
games|gaming play
desktop|computer work screen
laptop|computer work code
code|programming coding developer
bot|ai robot assistant
phone|call mobile screen time
phone_laptop|devices
plug_connected|charge power
battery_charge|charge energy
flash|energy power lightning bolt
home|house chores clean
home_heart|family home care
building_home|house chores
vacuum|clean chores housework
bed|make bed chores
cart|shopping groceries buy
shopping_bag|shopping buy
gift|present birthday give
wallet|money finance budget spend
savings|money save finance piggy
money|cash finance income
money_hand|cash finance income pay
receipt|bill expense
briefcase|work job career
backpack|school study trip
mail|email inbox message
chat|message talk conversation
people|friends social family community
people_community|friends social community
person|me self personal
person_heart|self care love
hand_wave|hello greet social
hand_right|stop pause
thumb_like|like good approve
emoji_smile_slight|mood happy smile
emoji_hand|mood
handshake|deal meet network
key|password access
lock_closed|private secure
shield|protect safe security
location|place map travel
airplane|travel trip flight
vehicle_car|drive car commute
vehicle_bus|commute transit
map|navigate travel
compass_northwest|direction explore
tent|camping outdoors
mountain|hike outdoors climb
beach|vacation sea
weather_sunny_high|sun summer
cloud|sky storage
wand|magic tidy
tag|label
bookmark|save read later
pin|keep important
alert|warning reminder
alert_on|reminder notification
bell|reminder notification
megaphone|announce speak
speaker_2|volume sound
shopping_bag|bag
tv|television watch
movies_and_tv|watch film
ticket_diagonal|event ticket
food_pizza|pizza
emoji_laugh|laugh funny
emoji_sad|sad mood
emoji_meh|neutral mood
mood_happy|happy
yoga|yoga stretch
person_standing|posture stand
person_arms_spread|stretch yoga
accessibility|body stretch
meditation|meditate calm mindful
sport|sport
steps|walk steps
 `.trim().split('\n').map(l => l.trim()).filter(Boolean);
const seen = new Set();
const out = [];
const missing = [];
for (const line of list) {
  const [name, tags = ''] = line.split('|');
  if (seen.has(name)) continue;
  seen.add(name);
  const file = dir + name + '_24_regular.svg';
  if (!fs.existsSync(file)) { missing.push(name); continue; }
  const svg = fs.readFileSync(file, 'utf8');
  const ds = [...svg.matchAll(/<path[^>]* d="([^"]+)"/g)].map(m => m[1]);
  if (!ds.length) { missing.push(name + '(nopath)'); continue; }
  const label = name.replace(/_/g, ' ');
  out.push({ name, label, tags, d: ds.join(' ') });
}
let kt = `package app.habitlab.ui\n\n// GENERATED by scratch/icons/gen.mjs from @fluentui/svg-icons (MIT, Microsoft). 24x24 regular style. Do not edit by hand.\n\ninternal class HabitIconDef(val key: String, val label: String, val tags: String, val path: String)\n\ninternal val HABIT_ICONS: List<HabitIconDef> = listOf(\n`;
for (const i of out) kt += `    HabitIconDef("${i.name}", "${i.label}", "${i.tags}", "${i.d}"),\n`;
kt += `)\n`;
fs.writeFileSync('HabitIcons.kt', kt);
console.log('icons', out.length, 'missing', missing.join(','), 'bytes', kt.length);
