//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//
// Protocol Buffers describing the Expansion API, an api for expanding
// transforms in a remote SDK.

// Code generated by protoc-gen-go. DO NOT EDIT.
// versions:
// 	protoc-gen-go v1.25.0-devel
// 	protoc        v3.13.0
// source: beam_expansion_api.proto

package jobmanagement_v1

import (
	context "context"
	pipeline_v1 "github.com/apache/beam/sdks/go/pkg/beam/model/pipeline_v1"
	grpc "google.golang.org/grpc"
	codes "google.golang.org/grpc/codes"
	status "google.golang.org/grpc/status"
	protoreflect "google.golang.org/protobuf/reflect/protoreflect"
	protoimpl "google.golang.org/protobuf/runtime/protoimpl"
	reflect "reflect"
	sync "sync"
)

const (
	// Verify that this generated code is sufficiently up-to-date.
	_ = protoimpl.EnforceVersion(20 - protoimpl.MinVersion)
	// Verify that runtime/protoimpl is sufficiently up-to-date.
	_ = protoimpl.EnforceVersion(protoimpl.MaxVersion - 20)
)

type ExpansionRequest struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	// Set of components needed to interpret the transform, or which
	// may be useful for its expansion.  This includes the input
	// PCollections (if any) to the to-be-expanded transform, along
	// with their coders and windowing strategies.
	Components *pipeline_v1.Components `protobuf:"bytes,1,opt,name=components,proto3" json:"components,omitempty"`
	// The actual PTransform to be expaneded according to its spec.
	// Its input should be set, but its subtransforms and outputs
	// should not be.
	Transform *pipeline_v1.PTransform `protobuf:"bytes,2,opt,name=transform,proto3" json:"transform,omitempty"`
	// A namespace (prefix) to use for the id of any newly created
	// components.
	Namespace string `protobuf:"bytes,3,opt,name=namespace,proto3" json:"namespace,omitempty"`
}

func (x *ExpansionRequest) Reset() {
	*x = ExpansionRequest{}
	if protoimpl.UnsafeEnabled {
		mi := &file_beam_expansion_api_proto_msgTypes[0]
		ms := protoimpl.X.MessageStateOf(protoimpl.Pointer(x))
		ms.StoreMessageInfo(mi)
	}
}

func (x *ExpansionRequest) String() string {
	return protoimpl.X.MessageStringOf(x)
}

func (*ExpansionRequest) ProtoMessage() {}

func (x *ExpansionRequest) ProtoReflect() protoreflect.Message {
	mi := &file_beam_expansion_api_proto_msgTypes[0]
	if protoimpl.UnsafeEnabled && x != nil {
		ms := protoimpl.X.MessageStateOf(protoimpl.Pointer(x))
		if ms.LoadMessageInfo() == nil {
			ms.StoreMessageInfo(mi)
		}
		return ms
	}
	return mi.MessageOf(x)
}

// Deprecated: Use ExpansionRequest.ProtoReflect.Descriptor instead.
func (*ExpansionRequest) Descriptor() ([]byte, []int) {
	return file_beam_expansion_api_proto_rawDescGZIP(), []int{0}
}

func (x *ExpansionRequest) GetComponents() *pipeline_v1.Components {
	if x != nil {
		return x.Components
	}
	return nil
}

func (x *ExpansionRequest) GetTransform() *pipeline_v1.PTransform {
	if x != nil {
		return x.Transform
	}
	return nil
}

func (x *ExpansionRequest) GetNamespace() string {
	if x != nil {
		return x.Namespace
	}
	return ""
}

type ExpansionResponse struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	// Set of components needed to execute the expanded transform,
	// including the (original) inputs, outputs, and subtransforms.
	Components *pipeline_v1.Components `protobuf:"bytes,1,opt,name=components,proto3" json:"components,omitempty"`
	// The expanded transform itself, with references to its outputs
	// and subtransforms.
	Transform *pipeline_v1.PTransform `protobuf:"bytes,2,opt,name=transform,proto3" json:"transform,omitempty"`
	// A set of requirements that must be appended to this pipeline's
	// requirements.
	Requirements []string `protobuf:"bytes,3,rep,name=requirements,proto3" json:"requirements,omitempty"`
	// (Optional) An string representation of any error encountered while
	// attempting to expand this transform.
	Error string `protobuf:"bytes,10,opt,name=error,proto3" json:"error,omitempty"`
}

func (x *ExpansionResponse) Reset() {
	*x = ExpansionResponse{}
	if protoimpl.UnsafeEnabled {
		mi := &file_beam_expansion_api_proto_msgTypes[1]
		ms := protoimpl.X.MessageStateOf(protoimpl.Pointer(x))
		ms.StoreMessageInfo(mi)
	}
}

func (x *ExpansionResponse) String() string {
	return protoimpl.X.MessageStringOf(x)
}

func (*ExpansionResponse) ProtoMessage() {}

func (x *ExpansionResponse) ProtoReflect() protoreflect.Message {
	mi := &file_beam_expansion_api_proto_msgTypes[1]
	if protoimpl.UnsafeEnabled && x != nil {
		ms := protoimpl.X.MessageStateOf(protoimpl.Pointer(x))
		if ms.LoadMessageInfo() == nil {
			ms.StoreMessageInfo(mi)
		}
		return ms
	}
	return mi.MessageOf(x)
}

// Deprecated: Use ExpansionResponse.ProtoReflect.Descriptor instead.
func (*ExpansionResponse) Descriptor() ([]byte, []int) {
	return file_beam_expansion_api_proto_rawDescGZIP(), []int{1}
}

func (x *ExpansionResponse) GetComponents() *pipeline_v1.Components {
	if x != nil {
		return x.Components
	}
	return nil
}

func (x *ExpansionResponse) GetTransform() *pipeline_v1.PTransform {
	if x != nil {
		return x.Transform
	}
	return nil
}

func (x *ExpansionResponse) GetRequirements() []string {
	if x != nil {
		return x.Requirements
	}
	return nil
}

func (x *ExpansionResponse) GetError() string {
	if x != nil {
		return x.Error
	}
	return ""
}

var File_beam_expansion_api_proto protoreflect.FileDescriptor

var file_beam_expansion_api_proto_rawDesc = []byte{
	0x0a, 0x18, 0x62, 0x65, 0x61, 0x6d, 0x5f, 0x65, 0x78, 0x70, 0x61, 0x6e, 0x73, 0x69, 0x6f, 0x6e,
	0x5f, 0x61, 0x70, 0x69, 0x2e, 0x70, 0x72, 0x6f, 0x74, 0x6f, 0x12, 0x22, 0x6f, 0x72, 0x67, 0x2e,
	0x61, 0x70, 0x61, 0x63, 0x68, 0x65, 0x2e, 0x62, 0x65, 0x61, 0x6d, 0x2e, 0x6d, 0x6f, 0x64, 0x65,
	0x6c, 0x2e, 0x65, 0x78, 0x70, 0x61, 0x6e, 0x73, 0x69, 0x6f, 0x6e, 0x2e, 0x76, 0x31, 0x1a, 0x15,
	0x62, 0x65, 0x61, 0x6d, 0x5f, 0x72, 0x75, 0x6e, 0x6e, 0x65, 0x72, 0x5f, 0x61, 0x70, 0x69, 0x2e,
	0x70, 0x72, 0x6f, 0x74, 0x6f, 0x22, 0xcc, 0x01, 0x0a, 0x10, 0x45, 0x78, 0x70, 0x61, 0x6e, 0x73,
	0x69, 0x6f, 0x6e, 0x52, 0x65, 0x71, 0x75, 0x65, 0x73, 0x74, 0x12, 0x4d, 0x0a, 0x0a, 0x63, 0x6f,
	0x6d, 0x70, 0x6f, 0x6e, 0x65, 0x6e, 0x74, 0x73, 0x18, 0x01, 0x20, 0x01, 0x28, 0x0b, 0x32, 0x2d,
	0x2e, 0x6f, 0x72, 0x67, 0x2e, 0x61, 0x70, 0x61, 0x63, 0x68, 0x65, 0x2e, 0x62, 0x65, 0x61, 0x6d,
	0x2e, 0x6d, 0x6f, 0x64, 0x65, 0x6c, 0x2e, 0x70, 0x69, 0x70, 0x65, 0x6c, 0x69, 0x6e, 0x65, 0x2e,
	0x76, 0x31, 0x2e, 0x43, 0x6f, 0x6d, 0x70, 0x6f, 0x6e, 0x65, 0x6e, 0x74, 0x73, 0x52, 0x0a, 0x63,
	0x6f, 0x6d, 0x70, 0x6f, 0x6e, 0x65, 0x6e, 0x74, 0x73, 0x12, 0x4b, 0x0a, 0x09, 0x74, 0x72, 0x61,
	0x6e, 0x73, 0x66, 0x6f, 0x72, 0x6d, 0x18, 0x02, 0x20, 0x01, 0x28, 0x0b, 0x32, 0x2d, 0x2e, 0x6f,
	0x72, 0x67, 0x2e, 0x61, 0x70, 0x61, 0x63, 0x68, 0x65, 0x2e, 0x62, 0x65, 0x61, 0x6d, 0x2e, 0x6d,
	0x6f, 0x64, 0x65, 0x6c, 0x2e, 0x70, 0x69, 0x70, 0x65, 0x6c, 0x69, 0x6e, 0x65, 0x2e, 0x76, 0x31,
	0x2e, 0x50, 0x54, 0x72, 0x61, 0x6e, 0x73, 0x66, 0x6f, 0x72, 0x6d, 0x52, 0x09, 0x74, 0x72, 0x61,
	0x6e, 0x73, 0x66, 0x6f, 0x72, 0x6d, 0x12, 0x1c, 0x0a, 0x09, 0x6e, 0x61, 0x6d, 0x65, 0x73, 0x70,
	0x61, 0x63, 0x65, 0x18, 0x03, 0x20, 0x01, 0x28, 0x09, 0x52, 0x09, 0x6e, 0x61, 0x6d, 0x65, 0x73,
	0x70, 0x61, 0x63, 0x65, 0x22, 0xe9, 0x01, 0x0a, 0x11, 0x45, 0x78, 0x70, 0x61, 0x6e, 0x73, 0x69,
	0x6f, 0x6e, 0x52, 0x65, 0x73, 0x70, 0x6f, 0x6e, 0x73, 0x65, 0x12, 0x4d, 0x0a, 0x0a, 0x63, 0x6f,
	0x6d, 0x70, 0x6f, 0x6e, 0x65, 0x6e, 0x74, 0x73, 0x18, 0x01, 0x20, 0x01, 0x28, 0x0b, 0x32, 0x2d,
	0x2e, 0x6f, 0x72, 0x67, 0x2e, 0x61, 0x70, 0x61, 0x63, 0x68, 0x65, 0x2e, 0x62, 0x65, 0x61, 0x6d,
	0x2e, 0x6d, 0x6f, 0x64, 0x65, 0x6c, 0x2e, 0x70, 0x69, 0x70, 0x65, 0x6c, 0x69, 0x6e, 0x65, 0x2e,
	0x76, 0x31, 0x2e, 0x43, 0x6f, 0x6d, 0x70, 0x6f, 0x6e, 0x65, 0x6e, 0x74, 0x73, 0x52, 0x0a, 0x63,
	0x6f, 0x6d, 0x70, 0x6f, 0x6e, 0x65, 0x6e, 0x74, 0x73, 0x12, 0x4b, 0x0a, 0x09, 0x74, 0x72, 0x61,
	0x6e, 0x73, 0x66, 0x6f, 0x72, 0x6d, 0x18, 0x02, 0x20, 0x01, 0x28, 0x0b, 0x32, 0x2d, 0x2e, 0x6f,
	0x72, 0x67, 0x2e, 0x61, 0x70, 0x61, 0x63, 0x68, 0x65, 0x2e, 0x62, 0x65, 0x61, 0x6d, 0x2e, 0x6d,
	0x6f, 0x64, 0x65, 0x6c, 0x2e, 0x70, 0x69, 0x70, 0x65, 0x6c, 0x69, 0x6e, 0x65, 0x2e, 0x76, 0x31,
	0x2e, 0x50, 0x54, 0x72, 0x61, 0x6e, 0x73, 0x66, 0x6f, 0x72, 0x6d, 0x52, 0x09, 0x74, 0x72, 0x61,
	0x6e, 0x73, 0x66, 0x6f, 0x72, 0x6d, 0x12, 0x22, 0x0a, 0x0c, 0x72, 0x65, 0x71, 0x75, 0x69, 0x72,
	0x65, 0x6d, 0x65, 0x6e, 0x74, 0x73, 0x18, 0x03, 0x20, 0x03, 0x28, 0x09, 0x52, 0x0c, 0x72, 0x65,
	0x71, 0x75, 0x69, 0x72, 0x65, 0x6d, 0x65, 0x6e, 0x74, 0x73, 0x12, 0x14, 0x0a, 0x05, 0x65, 0x72,
	0x72, 0x6f, 0x72, 0x18, 0x0a, 0x20, 0x01, 0x28, 0x09, 0x52, 0x05, 0x65, 0x72, 0x72, 0x6f, 0x72,
	0x32, 0x89, 0x01, 0x0a, 0x10, 0x45, 0x78, 0x70, 0x61, 0x6e, 0x73, 0x69, 0x6f, 0x6e, 0x53, 0x65,
	0x72, 0x76, 0x69, 0x63, 0x65, 0x12, 0x75, 0x0a, 0x06, 0x45, 0x78, 0x70, 0x61, 0x6e, 0x64, 0x12,
	0x34, 0x2e, 0x6f, 0x72, 0x67, 0x2e, 0x61, 0x70, 0x61, 0x63, 0x68, 0x65, 0x2e, 0x62, 0x65, 0x61,
	0x6d, 0x2e, 0x6d, 0x6f, 0x64, 0x65, 0x6c, 0x2e, 0x65, 0x78, 0x70, 0x61, 0x6e, 0x73, 0x69, 0x6f,
	0x6e, 0x2e, 0x76, 0x31, 0x2e, 0x45, 0x78, 0x70, 0x61, 0x6e, 0x73, 0x69, 0x6f, 0x6e, 0x52, 0x65,
	0x71, 0x75, 0x65, 0x73, 0x74, 0x1a, 0x35, 0x2e, 0x6f, 0x72, 0x67, 0x2e, 0x61, 0x70, 0x61, 0x63,
	0x68, 0x65, 0x2e, 0x62, 0x65, 0x61, 0x6d, 0x2e, 0x6d, 0x6f, 0x64, 0x65, 0x6c, 0x2e, 0x65, 0x78,
	0x70, 0x61, 0x6e, 0x73, 0x69, 0x6f, 0x6e, 0x2e, 0x76, 0x31, 0x2e, 0x45, 0x78, 0x70, 0x61, 0x6e,
	0x73, 0x69, 0x6f, 0x6e, 0x52, 0x65, 0x73, 0x70, 0x6f, 0x6e, 0x73, 0x65, 0x42, 0x72, 0x0a, 0x22,
	0x6f, 0x72, 0x67, 0x2e, 0x61, 0x70, 0x61, 0x63, 0x68, 0x65, 0x2e, 0x62, 0x65, 0x61, 0x6d, 0x2e,
	0x6d, 0x6f, 0x64, 0x65, 0x6c, 0x2e, 0x65, 0x78, 0x70, 0x61, 0x6e, 0x73, 0x69, 0x6f, 0x6e, 0x2e,
	0x76, 0x31, 0x42, 0x0c, 0x45, 0x78, 0x70, 0x61, 0x6e, 0x73, 0x69, 0x6f, 0x6e, 0x41, 0x70, 0x69,
	0x5a, 0x3e, 0x67, 0x69, 0x74, 0x68, 0x75, 0x62, 0x2e, 0x63, 0x6f, 0x6d, 0x2f, 0x61, 0x70, 0x61,
	0x63, 0x68, 0x65, 0x2f, 0x62, 0x65, 0x61, 0x6d, 0x2f, 0x73, 0x64, 0x6b, 0x73, 0x2f, 0x67, 0x6f,
	0x2f, 0x70, 0x6b, 0x67, 0x2f, 0x62, 0x65, 0x61, 0x6d, 0x2f, 0x6d, 0x6f, 0x64, 0x65, 0x6c, 0x2f,
	0x6a, 0x6f, 0x62, 0x6d, 0x61, 0x6e, 0x61, 0x67, 0x65, 0x6d, 0x65, 0x6e, 0x74, 0x5f, 0x76, 0x31,
	0x62, 0x06, 0x70, 0x72, 0x6f, 0x74, 0x6f, 0x33,
}

var (
	file_beam_expansion_api_proto_rawDescOnce sync.Once
	file_beam_expansion_api_proto_rawDescData = file_beam_expansion_api_proto_rawDesc
)

func file_beam_expansion_api_proto_rawDescGZIP() []byte {
	file_beam_expansion_api_proto_rawDescOnce.Do(func() {
		file_beam_expansion_api_proto_rawDescData = protoimpl.X.CompressGZIP(file_beam_expansion_api_proto_rawDescData)
	})
	return file_beam_expansion_api_proto_rawDescData
}

var file_beam_expansion_api_proto_msgTypes = make([]protoimpl.MessageInfo, 2)
var file_beam_expansion_api_proto_goTypes = []interface{}{
	(*ExpansionRequest)(nil),       // 0: org.apache.beam.model.expansion.v1.ExpansionRequest
	(*ExpansionResponse)(nil),      // 1: org.apache.beam.model.expansion.v1.ExpansionResponse
	(*pipeline_v1.Components)(nil), // 2: org.apache.beam.model.pipeline.v1.Components
	(*pipeline_v1.PTransform)(nil), // 3: org.apache.beam.model.pipeline.v1.PTransform
}
var file_beam_expansion_api_proto_depIdxs = []int32{
	2, // 0: org.apache.beam.model.expansion.v1.ExpansionRequest.components:type_name -> org.apache.beam.model.pipeline.v1.Components
	3, // 1: org.apache.beam.model.expansion.v1.ExpansionRequest.transform:type_name -> org.apache.beam.model.pipeline.v1.PTransform
	2, // 2: org.apache.beam.model.expansion.v1.ExpansionResponse.components:type_name -> org.apache.beam.model.pipeline.v1.Components
	3, // 3: org.apache.beam.model.expansion.v1.ExpansionResponse.transform:type_name -> org.apache.beam.model.pipeline.v1.PTransform
	0, // 4: org.apache.beam.model.expansion.v1.ExpansionService.Expand:input_type -> org.apache.beam.model.expansion.v1.ExpansionRequest
	1, // 5: org.apache.beam.model.expansion.v1.ExpansionService.Expand:output_type -> org.apache.beam.model.expansion.v1.ExpansionResponse
	5, // [5:6] is the sub-list for method output_type
	4, // [4:5] is the sub-list for method input_type
	4, // [4:4] is the sub-list for extension type_name
	4, // [4:4] is the sub-list for extension extendee
	0, // [0:4] is the sub-list for field type_name
}

func init() { file_beam_expansion_api_proto_init() }
func file_beam_expansion_api_proto_init() {
	if File_beam_expansion_api_proto != nil {
		return
	}
	if !protoimpl.UnsafeEnabled {
		file_beam_expansion_api_proto_msgTypes[0].Exporter = func(v interface{}, i int) interface{} {
			switch v := v.(*ExpansionRequest); i {
			case 0:
				return &v.state
			case 1:
				return &v.sizeCache
			case 2:
				return &v.unknownFields
			default:
				return nil
			}
		}
		file_beam_expansion_api_proto_msgTypes[1].Exporter = func(v interface{}, i int) interface{} {
			switch v := v.(*ExpansionResponse); i {
			case 0:
				return &v.state
			case 1:
				return &v.sizeCache
			case 2:
				return &v.unknownFields
			default:
				return nil
			}
		}
	}
	type x struct{}
	out := protoimpl.TypeBuilder{
		File: protoimpl.DescBuilder{
			GoPackagePath: reflect.TypeOf(x{}).PkgPath(),
			RawDescriptor: file_beam_expansion_api_proto_rawDesc,
			NumEnums:      0,
			NumMessages:   2,
			NumExtensions: 0,
			NumServices:   1,
		},
		GoTypes:           file_beam_expansion_api_proto_goTypes,
		DependencyIndexes: file_beam_expansion_api_proto_depIdxs,
		MessageInfos:      file_beam_expansion_api_proto_msgTypes,
	}.Build()
	File_beam_expansion_api_proto = out.File
	file_beam_expansion_api_proto_rawDesc = nil
	file_beam_expansion_api_proto_goTypes = nil
	file_beam_expansion_api_proto_depIdxs = nil
}

// Reference imports to suppress errors if they are not otherwise used.
var _ context.Context
var _ grpc.ClientConnInterface

// This is a compile-time assertion to ensure that this generated file
// is compatible with the grpc package it is being compiled against.
const _ = grpc.SupportPackageIsVersion6

// ExpansionServiceClient is the client API for ExpansionService service.
//
// For semantics around ctx use and closing/ending streaming RPCs, please refer to https://godoc.org/google.golang.org/grpc#ClientConn.NewStream.
type ExpansionServiceClient interface {
	Expand(ctx context.Context, in *ExpansionRequest, opts ...grpc.CallOption) (*ExpansionResponse, error)
}

type expansionServiceClient struct {
	cc grpc.ClientConnInterface
}

func NewExpansionServiceClient(cc grpc.ClientConnInterface) ExpansionServiceClient {
	return &expansionServiceClient{cc}
}

func (c *expansionServiceClient) Expand(ctx context.Context, in *ExpansionRequest, opts ...grpc.CallOption) (*ExpansionResponse, error) {
	out := new(ExpansionResponse)
	err := c.cc.Invoke(ctx, "/org.apache.beam.model.expansion.v1.ExpansionService/Expand", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

// ExpansionServiceServer is the server API for ExpansionService service.
type ExpansionServiceServer interface {
	Expand(context.Context, *ExpansionRequest) (*ExpansionResponse, error)
}

// UnimplementedExpansionServiceServer can be embedded to have forward compatible implementations.
type UnimplementedExpansionServiceServer struct {
}

func (*UnimplementedExpansionServiceServer) Expand(context.Context, *ExpansionRequest) (*ExpansionResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method Expand not implemented")
}

func RegisterExpansionServiceServer(s *grpc.Server, srv ExpansionServiceServer) {
	s.RegisterService(&_ExpansionService_serviceDesc, srv)
}

func _ExpansionService_Expand_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(ExpansionRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(ExpansionServiceServer).Expand(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/org.apache.beam.model.expansion.v1.ExpansionService/Expand",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(ExpansionServiceServer).Expand(ctx, req.(*ExpansionRequest))
	}
	return interceptor(ctx, in, info, handler)
}

var _ExpansionService_serviceDesc = grpc.ServiceDesc{
	ServiceName: "org.apache.beam.model.expansion.v1.ExpansionService",
	HandlerType: (*ExpansionServiceServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "Expand",
			Handler:    _ExpansionService_Expand_Handler,
		},
	},
	Streams:  []grpc.StreamDesc{},
	Metadata: "beam_expansion_api.proto",
}
