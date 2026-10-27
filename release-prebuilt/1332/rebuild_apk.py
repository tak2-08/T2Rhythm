"""Reconstruct the signed public APK from GitHub's public CI APK and public metadata.
No private key or arbitrary application payload is accepted by this recipe.
"""
from pathlib import Path
import hashlib,io,json,struct,sys,zipfile,zlib
LOCAL='<I5H3I2H';CENTRAL='<I6H3I5H2I';END='<I4H2IH'
def sha(b):return hashlib.sha256(b).hexdigest()
def unzero(records):
 return b''.join(bytes(n) if isinstance(n,int) else bytes.fromhex(n) for n in records)
def inspect(raw):
 with zipfile.ZipFile(io.BytesIO(raw)) as z:
  entries={};order=[];off=z.start_dir
  for info in z.infolist():
   cf=list(struct.unpack_from(CENTRAL,raw,off));assert cf[0]==0x02014b50
   begin=off+46;name=raw[begin:begin+cf[10]];extra=raw[begin+cf[10]:begin+cf[10]+cf[11]];comment=raw[begin+cf[10]+cf[11]:begin+cf[10]+cf[11]+cf[12]]
   lf=list(struct.unpack_from(LOCAL,raw,info.header_offset));assert lf[0]==0x04034b50 and not lf[2]&8
   at=info.header_offset+30;lname=raw[at:at+lf[9]];lextra=raw[at+lf[9]:at+lf[9]+lf[10]];data=raw[at+lf[9]+lf[10]:at+lf[9]+lf[10]+info.compress_size]
   assert name==lname and len(data)==cf[8] and lf[7]==cf[8]
   entries[info.filename]={'lf':lf,'cf':cf,'name':name,'le':lextra,'ce':extra,'comment':comment,'data':data,'offset':info.header_offset,'end':at+lf[9]+lf[10]+len(data)};order.append(info.filename)
   off=begin+cf[10]+cf[11]+cf[12]
  eof=list(struct.unpack_from(END,raw,off));assert eof[0]==0x06054b50
  assert off+22+eof[7]==len(raw)
  local_order=sorted(order,key=lambda n:entries[n]['offset'])
  assert entries[local_order[0]]['offset']==0
  for a,b in zip(local_order,local_order[1:]):assert entries[a]['end']==entries[b]['offset']
  return entries,order,local_order,eof,raw[off+22:],raw[entries[local_order[-1]]['end']:z.start_dir]
def rebuild(source,recipe):
 assert sha(source)==recipe['sourceApkSha256']
 entries,order,local_order,eof,comment,_=inspect(source)
 changes=recipe['entryChanges'];out=io.BytesIO();offsets={}
 with zipfile.ZipFile(io.BytesIO(source)) as base:
  for name in local_order:
   entry=entries[name];change=changes.get(name,{});lf=entry['lf'][:]
   for index,value in change.get('localFields',{}).items():lf[int(index)]=value
   extra=unzero(change['localExtra']) if 'localExtra' in change else entry['le']
   data=entry['data']
   if change.get('kind')=='public-module-metadata':
    original=base.read(name);assert sha(original)==recipe['module']['sourceSha256']
    value=bytearray(original)
    for edit in recipe['module']['byteEdits']:
     data_edit=bytes.fromhex(edit['hex']);value[edit['offset']:edit['offset']+len(data_edit)]=data_edit
    value=bytes(value);assert sha(value)==recipe['module']['targetSha256']
    with zipfile.ZipFile(io.BytesIO(value)) as module:
     assert json.loads(module.read('manifest.json'))==recipe['module']['signedManifest']
     assert module.read('manifest.sig').hex()==recipe['module']['publicSignatureHex']
    compressor=zlib.compressobj(6,zlib.DEFLATED,-15);data=compressor.compress(value)+compressor.flush()
   elif change.get('kind')=='public-entry-metadata':data=bytes.fromhex(change['compressedHex'])
   if 'compressedSha256' in change:assert sha(data)==change['compressedSha256'],(name,zlib.ZLIB_VERSION)
   assert len(data)==lf[7] and len(extra)==lf[10]
   offsets[name]=out.tell();out.write(struct.pack(LOCAL,*lf));out.write(entry['name']);out.write(extra);out.write(data)
 signature=unzero(recipe['publicApkSignatureBlock'])
 assert signature[-16:]==b'APK Sig Block 42' and struct.unpack_from('<Q',signature)[0]==len(signature)-8 and struct.unpack_from('<Q',signature,len(signature)-24)[0]==len(signature)-8
 out.write(signature);directory=out.tell()
 for name in order:
  entry=entries[name];change=changes.get(name,{});cf=entry['cf'][:]
  for index,value in change.get('centralFields',{}).items():cf[int(index)]=value
  cf[16]=offsets[name]
  extra=unzero(change['centralExtra']) if 'centralExtra' in change else entry['ce']
  assert len(extra)==cf[11]
  out.write(struct.pack(CENTRAL,*cf));out.write(entry['name']);out.write(extra);out.write(entry['comment'])
 eof[5]=out.tell()-directory;eof[6]=directory
 out.write(struct.pack(END,*eof));out.write(comment);result=out.getvalue()
 assert len(result)==recipe['targetApkSize'] and sha(result)==recipe['targetApkSha256']
 return result
if __name__=='__main__':
 source,recipe,out=map(Path,sys.argv[1:]);r=json.loads(recipe.read_text());data=rebuild(source.read_bytes(),r);out.write_bytes(data);print('Reconstructed verified public signed APK:',sha(data))
