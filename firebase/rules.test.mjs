import { readFile } from 'node:fs/promises';
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, deleteDoc, runTransaction, collection, getDocs, setLogLevel } from 'firebase/firestore';
setLogLevel('silent');

let env;
const initial = () => ({schemaVersion:1,revision:1,action:'CREATE',party:'Lender',mode:'I_OWE',total:10000,count:3,firstDue:'2026-01-31',frequency:'MONTHLY',payments:[],reversals:[],paid:{},paymentIds:{},reversed:{}});
const ref = (uid='alice', actor=uid, id='loan-1') => doc(actor ? env.authenticatedContext(actor).firestore() : env.unauthenticatedContext().firestore(), `users/${uid}/loans/${id}`);
const pay = (old, amount=1000, id='p1', index=0) => ({...old, revision:old.revision+1, action:'PAYMENT', payments:[...old.payments,{id,installment:index,amount,date:'2026-01-01'}],paid:{...old.paid,[index]:(old.paid[index]??0)+amount},paymentIds:{...old.paymentIds,[id]:true}});
const reverse = (old, index=0) => {
  const payment=old.payments[index];
  const paid={...old.paid,[payment.installment]:old.paid[payment.installment]-payment.amount};
  if(paid[payment.installment]===0) delete paid[payment.installment];
  return {...old,revision:old.revision+1,action:'REVERSAL',paid,reversed:{...old.reversed,[payment.id]:true},reversals:[...old.reversals,{paymentId:payment.id,paymentIndex:index,date:'2026-01-02',reason:'Wrong amount'}]};
};
before(async()=> { env=await initializeTestEnvironment({projectId:'demo-casha',firestore:{rules:await readFile('firestore.rules','utf8')}}); });
beforeEach(async()=>env.clearFirestore());
after(async()=>env?.cleanup());

test('owner can create, read and query their ledger',async()=>{
  await assertSucceeds(setDoc(ref(),initial())); await assertSucceeds(getDoc(ref()));
  assert.equal((await getDocs(collection(env.authenticatedContext('alice').firestore(),'users/alice/loans'))).size,1);
});
test('unauthenticated reads/writes are denied',async()=>{await assertFails(setDoc(ref('alice',null),initial()));await assertFails(getDoc(ref('alice',null)));});
test('cross-user reads/writes are denied',async()=>{await setDoc(ref(),initial());await assertFails(getDoc(ref('alice','bob')));await assertFails(setDoc(ref('alice','bob'),initial()));});
test('unknown fields and unsupported schema versions are denied',async()=>{await assertFails(setDoc(ref(),{...initial(),extra:true}));await assertFails(setDoc(ref(),{...initial(),schemaVersion:2}));});
test('invalid money and counts are denied',async()=>{for(const patch of [{total:0},{total:-1},{total:10.5},{total:10000000001},{count:0},{count:121},{total:1,count:2}])await assertFails(setDoc(ref(),{...initial(),...patch}));});
test('invalid dates and ambiguous twice-monthly first dates are denied',async()=>{for(const patch of [{firstDue:'2026-02-31'},{firstDue:'bad'},{frequency:'SEMIMONTHLY',firstDue:'2026-01-30'}])await assertFails(setDoc(ref(),{...initial(),...patch}));});
test('leap month end and 15th dates are accepted',async()=>{for(const date of ['2028-02-29','2026-02-28','2026-02-15'])await assertSucceeds(setDoc(ref('alice','alice',date),{...initial(),firstDue:date,frequency:'SEMIMONTHLY'}));});
test('creation cannot smuggle payment history or aggregate balance',async()=>{await assertFails(setDoc(ref(),{...pay(initial()),revision:1,action:'CREATE'}));await assertFails(setDoc(ref(),{...initial(),paid:{0:1000}}));});
test('unpaid schedule may be edited and revision must advance once',async()=>{await setDoc(ref(),initial());await assertSucceeds(setDoc(ref(),{...initial(),revision:2,action:'EDIT',total:20000,count:2}));await assertFails(setDoc(ref(),{...initial(),revision:2,action:'EDIT'}));});
test('partial payment and remaining-cent capacity are validated',async()=>{const a=initial();await setDoc(ref(),a);const b=pay(a,3334);await assertSucceeds(setDoc(ref(),b));await assertFails(setDoc(ref(),pay(b,1,'p2')));});
test('negative, zero, fractional, wrong-index and overpayments are denied',async()=>{const a=initial();await setDoc(ref(),a);for(const [amount,index] of [[0,0],[-1,0],[1.5,0],[3335,0],[1,-1],[1,3]])await assertFails(setDoc(ref(),pay(a,amount,'p1',index)));});
test('payment requires a matching aggregate update',async()=>{const a=initial();await setDoc(ref(),a);await assertFails(setDoc(ref(),{...pay(a),paid:{}}));await assertFails(setDoc(ref(),{...pay(a),paid:{0:1000,1:1}}));});
test('history and recorded IDs cannot be rewritten or reused',async()=>{const a=pay(initial());await setDoc(ref(),initial());await setDoc(ref(),a);await assertFails(setDoc(ref(),pay(a,1,'p1')));const b=pay(a,1,'p2');b.payments[0]={...b.payments[0],amount:999};await assertFails(setDoc(ref(),b));});
test('paid loan terms are locked but name can change',async()=>{const a=pay(initial());await setDoc(ref(),initial());await setDoc(ref(),a);await assertFails(setDoc(ref(),{...a,revision:3,action:'EDIT',total:20000}));await assertSucceeds(setDoc(ref(),{...a,revision:3,action:'EDIT',party:'Renamed'}));});
test('reversal restores balance and preserves the original payment',async()=>{const a=pay(initial());await setDoc(ref(),initial());await setDoc(ref(),a);const b=reverse(a);await assertSucceeds(setDoc(ref(),b));const saved=(await getDoc(ref())).data();assert.equal(saved.payments.length,1);assert.equal(saved.paid['0']??0,0);await assertFails(setDoc(ref(),reverse(b)));});
test('reversal cannot reference a different payment or remove paid history',async()=>{const a=pay(initial());await setDoc(ref(),initial());await setDoc(ref(),a);const b=reverse(a);await assertFails(setDoc(ref(),{...b,reversals:[{...b.reversals[0],paymentId:'other'}]}));await assertFails(setDoc(ref(),{...b,payments:[]}));});
test('reversal needs reason and cannot predate payment',async()=>{const a=pay(initial());await setDoc(ref(),initial());await setDoc(ref(),a);const b=reverse(a);for(const patch of [{reason:''},{date:'2025-12-31'}])await assertFails(setDoc(ref(),{...b,reversals:[{...b.reversals[0],...patch}]}));});
test('reversed loans cannot rewrite their original terms',async()=>{await setDoc(ref(),initial());const a=pay(initial());await setDoc(ref(),a);const b=reverse(a);await setDoc(ref(),b);await assertFails(setDoc(ref(),{...b,revision:4,action:'EDIT',count:2}));});
test('deletion and undeclared collections are denied',async()=>{await setDoc(ref(),initial());await assertFails(deleteDoc(ref()));await assertFails(setDoc(doc(env.authenticatedContext('alice').firestore(),'users/alice/transactions/p1'),{amount:1}));});
test('stale concurrent transactions do not overwrite each other',async()=>{
  await setDoc(ref(),initial());
  const attempt=(id)=>{
    const db=env.authenticatedContext('alice').firestore(); const target=doc(db,'users/alice/loans/loan-1');
    return runTransaction(db,async tx=>{
      const snap=await tx.get(target); if(snap.data().revision!==1) throw new Error('Conflict'); tx.set(target,pay(snap.data(),1000,id));
    });
  };
  const results=await Promise.allSettled([attempt('p1'),attempt('p2')]);
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  assert.equal((await getDoc(ref())).data().payments.length,1);
});
test('whitespace-only names and reversal reasons are denied',async()=>{
  await assertFails(setDoc(ref(),{...initial(),party:' \n '}));
  await setDoc(ref(),initial());const a=pay(initial());await setDoc(ref(),a);const b=reverse(a);
  await assertFails(setDoc(ref(),{...b,reversals:[{...b.reversals[0],reason:' \n '}]}));
});
test('500-payment boundary is usable and cannot be exceeded',async()=>{
  let old=initial(); for(let i=0;i<499;i++) old=pay(old,1,`p${i}`);
  await env.withSecurityRulesDisabled(async context=>{await setDoc(doc(context.firestore(),'users/alice/loans/loan-1'),old);});
  const last=pay(old,1,'p499'); await assertSucceeds(setDoc(ref(),last));
  await assertFails(setDoc(ref(),pay(last,1,'p500')));
});

const tombstone=(revision)=>({schemaVersion:1,revision,action:'DELETE',deleted:true});
test('owner can replace an unpaid loan with a minimal deletion marker',async()=>{
 await setDoc(ref(),initial());await assertSucceeds(setDoc(ref(),tombstone(2)));
 assert.deepEqual((await getDoc(ref())).data(),tombstone(2));
 await assertFails(setDoc(ref(),{...initial(),revision:3,action:'EDIT'}));
 await assertFails(setDoc(ref(),initial()));
 await assertFails(deleteDoc(ref()));
});
test('deletion cannot erase payments or reversed history',async()=>{
 await setDoc(ref(),initial());const paid=pay(initial());await setDoc(ref(),paid);
 await assertFails(setDoc(ref(),tombstone(3)));await setDoc(ref(),reverse(paid));
 await assertFails(setDoc(ref(),tombstone(4)));
});
test('deletion requires owner, exact revision and minimal marker',async()=>{
 await setDoc(ref(),initial());await assertFails(setDoc(ref('alice','bob'),tombstone(2)));
 await assertFails(setDoc(ref('alice',null),tombstone(2)));
 await assertFails(setDoc(ref(),tombstone(3)));
 await assertFails(setDoc(ref(),{...tombstone(2),party:'Retained'}));
 await assertFails(setDoc(ref('alice','alice','nonexistent'),tombstone(1)));
});
