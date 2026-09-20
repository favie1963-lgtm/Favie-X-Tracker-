/**
 * Target selection and locking checks.
 *
 * The behaviour under test is the whole point of the redesign: the user picks an
 * object, the tracker follows *that* object, and it never silently substitutes a
 * different one. The failure this guards against is a tracker that latches onto
 * whichever object happens to be easiest to find.
 *
 * Run with: node scripts/check-target-tracking.mjs
 */
import { ObjectTracker, TargetTracker, TARGET_STATE, bboxIou } from '../src/services/vision/tracker.js';

let failures = 0;
function check(label, condition, detail = '') {
  if (!condition) failures += 1;
  console.log(`${condition ? 'PASS' : 'FAIL'} ${label}${detail ? ` (${detail})` : ''}`);
}

const W = 320;
const H = 240;

/** RGBA frame with solid rectangles painted on a mid-grey ground. */
function makeFrame(rects) {
  const data = new Uint8ClampedArray(W * H * 4);
  for (let i = 0; i < W * H; i += 1) {
    const p = i * 4;
    data[p] = 90;
    data[p + 1] = 90;
    data[p + 2] = 90;
    data[p + 3] = 255;
  }
  for (const rect of rects) {
    for (let y = rect.y; y < rect.y + rect.height; y += 1) {
      for (let x = rect.x; x < rect.x + rect.width; x += 1) {
        const p = (y * W + x) * 4;
        data[p] = rect.color[0];
        data[p + 1] = rect.color[1];
        data[p + 2] = rect.color[2];
        data[p + 3] = 255;
      }
    }
  }
  return { data, width: W, height: H };
}

const RED = [220, 30, 30];
const BLUE = [30, 60, 220];

/** Object A, and an identical-shaped decoy B of the same colour. */
const objectA = { x: 40, y: 90, width: 44, height: 44, color: RED };
const decoyB = { x: 220, y: 90, width: 44, height: 44, color: RED };
const blueC = { x: 130, y: 170, width: 40, height: 40, color: BLUE };

const detector = new ObjectTracker();
const target = new TargetTracker();

// --- 1. Selection ---------------------------------------------------------

const frame1 = makeFrame([objectA, decoyB, blueC]);
const dets1 = detector.detectObjects(frame1);

const tap = { x: objectA.x + objectA.width / 2, y: objectA.y + objectA.height / 2 };
const selected = target.select(frame1, dets1, tap);

check('selection locks the target', selected.state === TARGET_STATE.LOCKED, selected.state);
check('selection state reports a target', Boolean(selected.target));
check(
  'selected box sits on the tapped object, not the decoy',
  bboxIou(selected.target.bbox, objectA) > 0.6,
  `iouA=${bboxIou(selected.target.bbox, objectA).toFixed(2)} iouB=${bboxIou(
    selected.target.bbox,
    decoyB,
  ).toFixed(2)}`,
);

// --- 2. Following the selected object -------------------------------------

// A moves right and down; the decoy does not move. A tracker that switched
// objects would report the decoy's position instead.
const movedA = { ...objectA, x: 66, y: 104 };
const frame2 = makeFrame([movedA, decoyB, blueC]);
const dets2 = detector.detectObjects(frame2);
const afterMove = target.update(frame2, dets2);

check('target stays locked after movement', afterMove.state === TARGET_STATE.LOCKED);
check(
  'marker follows the moved object',
  bboxIou(afterMove.target.bbox, movedA) > 0.5,
  `iouMoved=${bboxIou(afterMove.target.bbox, movedA).toFixed(2)} iouDecoy=${bboxIou(
    afterMove.target.bbox,
    decoyB,
  ).toFixed(2)}`,
);
check(
  'marker did not jump to the stationary decoy',
  bboxIou(afterMove.target.bbox, decoyB) < 0.2,
  `iouDecoy=${bboxIou(afterMove.target.bbox, decoyB).toFixed(2)}`,
);

// --- 3. Occlusion and target loss -----------------------------------------

// The tracked object vanishes; the decoy remains. The tracker must report LOST
// and keep the last known box, never adopt the decoy.
let lostReport = null;
for (let i = 0; i < 12; i += 1) {
  const frame = makeFrame([decoyB, blueC]);
  const dets = detector.detectObjects(frame);
  lostReport = target.update(frame, dets);
}
check('target is reported lost when it disappears', lostReport.state === TARGET_STATE.LOST, lostReport.state);
check(
  'lost target did not silently become the decoy',
  bboxIou(lostReport.target.bbox, decoyB) < 0.2,
  `iouDecoy=${bboxIou(lostReport.target.bbox, decoyB).toFixed(2)}`,
);
check(
  'lost target keeps its last known position',
  bboxIou(lostReport.target.bbox, movedA) > 0.3,
  `iouLastKnown=${bboxIou(lostReport.target.bbox, movedA).toFixed(2)}`,
);

// --- 4. Explicit reacquire -------------------------------------------------

// The object reappears where it was; reacquiring from the last known position
// should find it again.
const frame4 = makeFrame([movedA, decoyB, blueC]);
const dets4 = detector.detectObjects(frame4);
const reacquired = target.select(frame4, dets4, {
  x: lostReport.target.centroid.x,
  y: lostReport.target.centroid.y,
});
check('reacquire restores the lock', reacquired.state === TARGET_STATE.LOCKED, reacquired.state);
check(
  'reacquire returns to the original object',
  bboxIou(reacquired.target.bbox, movedA) > 0.5,
  `iou=${bboxIou(reacquired.target.bbox, movedA).toFixed(2)}`,
);

// --- 5. Selection without a detector hit ----------------------------------

// An object the detector does not report is still trackable, because the tap
// itself defines the template. This one is 16x16 with a darker stripe: the body
// contour is 256px^2 and the stripe 96px^2, both under the 500px^2 edge-candidate
// floor, and grey matches no colour range, so the detector reports nothing on
// either pass. The stripe gives the template the internal structure a matcher
// needs. A featureless block has no such structure and is genuinely untrackable
// by appearance alone, so it is not asserted here.
const greyObject = { x: 200, y: 40, width: 16, height: 16, color: [110, 110, 110] };
const greyStripe = { x: 210, y: 40, width: 6, height: 16, color: [30, 30, 30] };

const frame5 = makeFrame([greyObject, greyStripe]);
const dets5 = detector.detectObjects(frame5);
check('the small grey object is not reported by the detector', dets5.length === 0, `${dets5.length} dets`);

const greyLock = target.select(frame5, dets5, { x: 208, y: 48 });
check('a tap on an undetected object still locks', greyLock.state === TARGET_STATE.LOCKED);
check('the lock does not claim a detector colour', greyLock.target.color === 'unknown');

// The follow check compares the whole locked region moved by the same delta, so
// it does not depend on the tap-centred box happening to align with the object.
const lockedBox = { ...greyLock.target.bbox };
const dx = 20;
const dy = 16;
const frame6 = makeFrame([
  { ...greyObject, x: greyObject.x + dx, y: greyObject.y + dy },
  { ...greyStripe, x: greyStripe.x + dx, y: greyStripe.y + dy },
]);
const greyFollow = target.update(frame6, detector.detectObjects(frame6));
const expectedBox = { ...lockedBox, x: lockedBox.x + dx, y: lockedBox.y + dy };
check(
  'the undetected object is still followed after it moves',
  bboxIou(greyFollow.target.bbox, expectedBox) > 0.7,
  `iou=${bboxIou(greyFollow.target.bbox, expectedBox).toFixed(2)}`,
);

// --- 6. Clearing ----------------------------------------------------------

target.reset();
const cleared = target.report();
check('reset clears the target', cleared.state === TARGET_STATE.NONE && cleared.target === null);

console.log(failures === 0 ? '\nTARGET TRACKING OK' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);